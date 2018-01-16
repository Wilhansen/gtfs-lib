package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.model.Entity;
import com.conveyal.gtfs.storage.StorageException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.dbutils.DbUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.conveyal.gtfs.loader.JdbcGtfsLoader.INSERT_BATCH_SIZE;

/**
 * This wraps a single database table and provides methods to modify GTFS entities.
 */
public class JdbcTableWriter implements TableWriter {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcTableWriter.class);
    private final DataSource dataSource;
    private final Table specTable;
    private final String tablePrefix;
    private static Gson gson = new GsonBuilder().create();

    /**
     * Enum containing available methods for updating in SQL.
     */
    private enum SqlMethod {
        DELETE, UPDATE, CREATE
    }

    public JdbcTableWriter (Table specTable, DataSource dataSource, String tablePrefix) {
        this.tablePrefix = tablePrefix;
        this.dataSource = dataSource;
        this.specTable = specTable;
    }

    private static JsonObject getJsonObject(String json) {
        JsonElement jsonElement;
        try {
            jsonElement = gson.fromJson(json, JsonElement.class);
        } catch (JsonSyntaxException e) {
            LOG.error("Bad JSON syntax", e);
            throw e;
        }
        return jsonElement.getAsJsonObject();
    }

    @Override
    public String create(String json) throws SQLException {
        return update(null, json);
    }

    @Override
    public String update(Integer id, String json) throws SQLException {
        final boolean isCreating = id == null;
        JsonObject jsonObject = getJsonObject(json);
        String tableName = String.join(".", tablePrefix, specTable.name);
        // Parse the fields/values into a Field -> String map (drops ALL unknown fields)
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            // Ensure that the key field is unique and that referencing tables are updated if the value is updated.
            ensureReferentialIntegrity(connection, jsonObject, tablePrefix, specTable, id);
            // This must follow referential integrity check because some tables will modify the jsonObject (e.g., adding trip ID if it is null).
            Map<Field, String> fieldStringMap = jsonToFieldStringMap(jsonObject, specTable);
            LOG.info(jsonObject.toString());
            PreparedStatement preparedStatement = createPreparedUpdate(id, isCreating, fieldStringMap, tableName, connection, false);
            // ID from create/update result
            long newId = handleStatementExecution(connection, preparedStatement, isCreating);
            // At this point, the transaction was successful (and committed). Now we should handle any post-save
            // logic. For example, after saving a trip, we need to store its stop times.
            Set<Table> referencingTables = getReferencingTables(specTable);
            // FIXME: hacky hack hack to add shapes table if we're updating a pattern.
            if (specTable.name.equals("patterns")) {
                referencingTables.add(Table.SHAPES);
            }
            for (Table referencingTable : referencingTables) {
                Table parentTable = referencingTable.getParentTable();
                if (parentTable != null && parentTable.name.equals(specTable.name) || referencingTable.name.equals("shapes")) {
                    // If a referencing table has the current table as its parent, update child elements.
                    JsonElement childEntities = jsonObject.get(referencingTable.name);
                    if (childEntities == null) {
                        throw new SQLException(String.format("Child entities %s must not be null", referencingTable.name));
                    }
                    int entityId = isCreating ? (int) newId : id;
                    updateChildTable(childEntities.getAsJsonArray(), entityId, isCreating, referencingTable, connection);
                }
            }
            // Add new ID to JSON object.
            jsonObject.addProperty("id", newId);
            // FIXME: Should this return the entity from the database?
            return jsonObject.toString();
        } catch (SQLException e) {
            LOG.error("Error updating entity", e);
            DbUtils.closeQuietly(connection);
            throw e;
        }
    }

    /**
     * Creates a prepared statement for an entity update or create operation. Uses a map of Field objects with values to
     * construct SQL statement for the given table name.
     */
    private static PreparedStatement createPreparedUpdate(Integer id, boolean isCreating, Map<Field, String> fieldStringMap, String tableName, Connection connection, boolean batch) throws SQLException {
        String statementString;
        // Collect field names for string joining from JsonObject.
        List<String> fieldNames = fieldStringMap.keySet().stream()
                // If updating, add suffix for use in set clause
                .map(field -> isCreating ? field.getName() : field.getName() + " = ?")
                // Ensure fields are set according to alphabetical order of field name
                // This order MUST coincide with whatever function is used to fill values (see fillValues)
                .sorted()
                .collect(Collectors.toList());
        if (isCreating) {
            // If creating a new entity, use default next value for ID.
            String questionMarks = String.join(", ", Collections.nCopies(fieldNames.size(), "?"));
            statementString = String.format("insert into %s (id, %s) values (DEFAULT, %s)", tableName, String.join(", ", fieldNames), questionMarks);
        } else {
            // If updating an existing entity, specify record to update fields found with where clause on id.
            statementString = String.format("update %s set %s where id = %d", tableName, String.join(", ", fieldNames), id);
        }
        // Prepare statement and set RETURN_GENERATED_KEYS to get the entity id back
        PreparedStatement preparedStatement = connection.prepareStatement(
                statementString,
                Statement.RETURN_GENERATED_KEYS);
        if (!batch) {
            fillValues(fieldStringMap, preparedStatement, connection);
        }
        return preparedStatement;
    }

    private static void fillValues(Map<Field, String> fieldStringMap, PreparedStatement preparedStatement, Connection connection) throws SQLException {
        // one-based index for statement parameters
        int index = 1;
        // Sort keys on field name to match prepared statement parameters
        Field[] keys = fieldStringMap.keySet().stream()
                .sorted(Comparator.comparing(Field::getName))
                .toArray(Field[]::new);
        // Fill statement parameters with strings from JSON
        for (Field field : keys) {
            String value = fieldStringMap.get(field);
//            LOG.info("{}={}", field.name, value);
            try {
                if (value == null) {
                    // Handle null value
                    preparedStatement.setNull(index, field.getSqlType().getVendorTypeNumber());
                } else {
                    field.setParameter(preparedStatement, index, value);
                }
            } catch (StorageException e) {
                if (field.name.contains("_time")) {
                    // FIXME: This is a hack to get arrival and departure time into the right format. Because the UI
                    // currently returns them as seconds since midnight rather than the Field-defined format HH:MM:SS.
                    try {
                        if (value == null) {
                            preparedStatement.setNull(index, field.getSqlType().getVendorTypeNumber());
                        } else {
                            Integer seconds = Integer.parseInt(value);
                            preparedStatement.setInt(index, seconds);
                        }
                    } catch (NumberFormatException ex) {
                        connection.rollback();
                        LOG.error("Bad column: {}={}", field.name, value);
                        ex.printStackTrace();
                        throw ex;
                    }
                } else {
                    connection.rollback();
                    throw e;
                }
            }
            index += 1;
        }
    }

    /**
     * This updates
     */
    private void updateChildTable(JsonArray entityList, Integer id, boolean isCreating, Table subTable, Connection connection) throws SQLException {
        // Get parent table's key field
        String keyField;
        String keyValue;
        boolean updatingShapes = subTable.name.equals("shapes");
        if (updatingShapes) {
            keyField = "shape_id";
        } else {
            keyField = specTable.getKeyFieldName();
        }
        // Get parent entity's key value
        keyValue = getValueForId(id, keyField, tablePrefix, specTable, connection);
        String childTableName = String.join(".", tablePrefix, subTable.name);
        // FIXME: add check for pattern stop consistency.
        // FIXME: re-order stop times if pattern stop order changes.
        // FIXME: allow shapes to be updated on pattern geometry change.
        if (!isCreating) {
            String deleteSql;
            // Delete existing sub-entities for given entity ID if the parent entity is not being created
            deleteSql = getUpdateReferencesSql(SqlMethod.DELETE, childTableName, keyField, keyValue, null);
            LOG.info(deleteSql);
            Statement statement = connection.createStatement();
            // FIXME: Copy on update (instead of deleting here)
            int result = statement.executeUpdate(deleteSql);
            LOG.info("Deleted {}", result);
            // FIXME: are there cases when an update should not return zero?
//            if (result == 0) throw new SQLException("No stop times found for trip ID");
        }
        int entityCount = 0;
        PreparedStatement insertStatement = null;
        for (JsonElement entityElement : entityList) {
            JsonObject entity = entityElement.getAsJsonObject();
            if (entity.get(keyField) == null) {
                entity.addProperty(keyField, keyValue);
            }
            Map<Field, String> fieldStringMap = jsonToFieldStringMap(entity, subTable);
            // Insert new sub-entity.
            if (entityCount == 0) {
                insertStatement = createPreparedUpdate(id, true, fieldStringMap, childTableName, connection, true);
            }
            LOG.info("{}", entityCount);
            fillValues(fieldStringMap, insertStatement, connection);
            if (entityCount == 0) LOG.info(insertStatement.toString());
            insertStatement.addBatch();
            if (entityCount % INSERT_BATCH_SIZE == 0) {
                LOG.info("Executing batch insert ({}/{}) for {}", entityCount, entityList.size(), childTableName);
                int[] newIds = insertStatement.executeBatch();
                LOG.info("Updated {}", newIds.length);
            }
            entityCount += 1;
//            handleStatementExecution(connection, insertStatement, true);
        }
        // execute any remaining prepared statement calls
        LOG.info("Executing batch insert ({}/{}) for {}", entityCount, entityList.size(), childTableName);
        if (insertStatement != null) {
            // If insert statement is null, an empty array was passed for the child table, so the child elements have
            // been wiped.
            int[] newIds = insertStatement.executeBatch();
            LOG.info("Updated {}", newIds.length);
        } else {
            LOG.info("No inserts to execute. Empty array found in JSON for child table {}", childTableName);
        }
        LOG.info("Committing transaction.");
        connection.commit();
    }


    @Override
    public int delete(Integer id) throws SQLException {
        Connection connection = dataSource.getConnection();
        // Handle "cascading" delete or constraints on deleting entities that other entities depend on
        // (e.g., keep a calendar from being deleted if trips reference it).
        // FIXME: actually add "cascading"? Currently, it just deletes one level down.
        deleteFromReferencingTables(tablePrefix, specTable, connection, id);
        PreparedStatement statement = connection.prepareStatement(specTable.generateDeleteSql(tablePrefix));
        statement.setInt(1, id);
        LOG.info(statement.toString());
        // Execute query
        int result = statement.executeUpdate();
        connection.commit();
        if (result == 0) {
            throw new SQLException("Could not delete entity");
        }
        // FIXME: change return message based on result value
        return result;
    }

    @Override
    public void commit() throws SQLException {
        // FIXME: should this take a connection and commit it?
    }

    /**
     * Delete entities from any referencing tables (if required). This method is defined for convenience and clarity, but
     * essentially just runs updateReferencingTables with a null value for newKeyValue param.
     */
    private static void deleteFromReferencingTables(String namespace, Table table, Connection connection, int id) throws SQLException {
        updateReferencingTables(namespace, table, connection, id, null);
    }

    /**
     * Handles converting JSON object into a map from Fields (which have methods to set parameter type in SQL
     * statements) to String values found in the JSON.
     */
    private static Map<Field, String> jsonToFieldStringMap(JsonObject jsonObject, Table table) throws SQLException {
        HashMap<Field, String> fieldStringMap = new HashMap<>();
        // Iterate over fields in JSON and remove those not found in table.
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            // TODO: clean field names? Currently, unknown fields are just skipped, but in the future, if a bad
            // field name is found, we will want to throw an exception, log an error, and halt.
            if (!table.hasField(entry.getKey())) {
                // Skip all unknown fields (this includes id field because it is not listed in table fields)
                continue;
            }
            Field field = table.getFieldForName(entry.getKey());
            JsonElement value = entry.getValue();
            // FIXME: need to be able to set fields to null and handle empty strings -> null
            if (!value.isJsonNull()) {
                // Only add non-null fields to map
                fieldStringMap.put(field, value.getAsString());
            } else {
                fieldStringMap.put(field, null);
            }
        }
        if (fieldStringMap.keySet().size() == 0) {
            throw new SQLException("Zero valid fields found in JSON object");
        }
//        LOG.info("Found {} valid fields", fieldStringMap.keySet().size());
        return fieldStringMap;
    }

    /**
     * Handle executing a prepared statement and return the ID for the newly-generated or updated entity.
     */
    private static long handleStatementExecution(Connection connection, PreparedStatement statement, boolean isCreating) throws SQLException {
        // Log the SQL for the prepared statement
        LOG.info(statement.toString());
        int affectedRows = statement.executeUpdate();
        // Commit the transaction
        connection.commit();
        // Determine operation-specific action for any error messages
        String messageAction = isCreating ? "Creating" : "Updating";
        if (affectedRows == 0) {
            // No update occurred.
            // TODO: add some clarity on cause (e.g., where clause found no entity with provided ID)?
            throw new SQLException(messageAction + " entity failed, no rows affected.");
        }
        try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
            if (generatedKeys.next()) {
                // Get the auto-generated ID from the update execution
                long newId = generatedKeys.getLong(1);
                return newId;
            } else {
                throw new SQLException(messageAction + " entity failed, no ID obtained.");
            }
        }
    }

    /**
     * Checks for modification of GTFS key field (e.g., stop_id, route_id) in supplied JSON object and ensures
     * both uniqueness and that referencing tables are appropriately updated.
     */
    private static void ensureReferentialIntegrity(Connection connection, JsonObject jsonObject, String namespace, Table table, Integer id) throws SQLException {
        final boolean isCreating = id == null;
        String keyField = table.getKeyFieldName();
        if (jsonObject.get(keyField) == null || jsonObject.get(keyField).isJsonNull()) {
            // FIXME: generate key field automatically for certain entities (e.g., trip ID). Maybe this should be
            // generated for all entities if null?
            if ("trip_id".equals(keyField)) {
                jsonObject.addProperty(keyField, UUID.randomUUID().toString());
            } else {
                throw new SQLException(String.format("Key field %s must not be null", keyField));
            }
        }
        String keyValue = jsonObject.get(keyField).getAsString();
        String tableName = String.join(".", namespace, table.name);
        // If updating key field, check that there is no ID conflict on value (e.g., stop_id or route_id)
        TIntSet uniqueIds = getIdsForCondition(tableName, keyField, keyValue, connection);
        int size = uniqueIds.size();
        if (size == 0 || (size == 1 && id != null && uniqueIds.contains(id))) {
            // OK.
            if (size == 0 && !isCreating) {
                // FIXME: Need to update referencing tables because entity has changed ID.
                // Entity key value is being changed to an entirely new one.  If there are entities that
                // reference this value, we need to update them.
                updateReferencingTables(namespace, table, connection, id, keyValue);
            }
        } else {
            // Conflict. The different conflict conditions are outlined below.
            if (size == 1) {
                // There was one match found.
                if (isCreating) {
                    // Under no circumstance should a new entity have a conflict with existing key field.
                    throw new SQLException("New entity's key field must not match existing value.");
                }
                if (!uniqueIds.contains(id)) {
                    // There are two circumstances we could encounter here.
                    // 1. The key value for this entity has been updated to match some other entity's key value (conflict).
                    // 2. The int ID provided in the request parameter does not match any rows in the table.
                    throw new SQLException("Key field must be unique and request parameter ID must exist.");
                }
            } else if (size > 1) {
                // FIXME: Handle edge case where original data set contains duplicate values for key field and this is an
                // attempt to rectify bad data.
                LOG.warn("{} entity shares the same key field ({}={})! This is Bad!!", size, keyField, keyValue);
                throw new SQLException("More than one entity must not share the same id field");
            }
        }
    }

    /**
     * For some condition (where field = string value), return the set of unique int IDs for the records that match.
     */
    private static TIntSet getIdsForCondition(String tableName, String keyField, String keyValue, Connection connection) throws SQLException {
        String idCheckSql = String.format("select * from %s where %s = '%s'", tableName, keyField, keyValue);
        LOG.info(idCheckSql);
        // Create statement for counting rows selected
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(idCheckSql);
        // Keep track of number of records found with key field
        TIntSet uniqueIds = new TIntHashSet();
        while (resultSet.next()) {
            int uniqueId = resultSet.getInt(1);
            uniqueIds.add(uniqueId);
            LOG.info("id: {}, name: {}", uniqueId, resultSet.getString(4));
        }
        return uniqueIds;
    }

    /**
     * Finds the tables that reference
     */
    private static Set<Table> getReferencingTables(Table table) {
        String keyField = table.getKeyFieldName();
        Set<Table> referencingTables = new HashSet<>();
        for (Table gtfsTable : Table.tablesInOrder) {
            // IMPORTANT: Skip the table for the entity we're modifying or if loop table does not have field.
            if (table.name.equals(gtfsTable.name) || !gtfsTable.hasField(keyField)) continue;
            Field tableField = gtfsTable.getFieldForName(keyField);
            // If field is not a foreign reference, continue. (This should probably never be the case because a field
            // that shares the key field's name ought to refer to the key field.
            if (!tableField.isForeignReference()) continue;
            referencingTables.add(gtfsTable);
        }
        return referencingTables;
    }

    /**
     * For a given integer ID, return the key field (e.g., stop_id) of that entity.
     */
    private static String getValueForId(int id, String fieldName, String namespace, Table table, Connection connection) throws SQLException {
        String tableName = String.join(".", namespace, table.name);
        String selectIdSql = String.format("select %s from %s where id = %d", fieldName, tableName, id);
        LOG.info(selectIdSql);
        Statement selectIdStatement = connection.createStatement();
        ResultSet selectResults = selectIdStatement.executeQuery(selectIdSql);
        String keyValue = null;
        while (selectResults.next()) {
            keyValue = selectResults.getString(1);
        }
        return keyValue;
    }

    /**
     * Updates any foreign references that exist should a GTFS key field (e.g., stop_id or route_id) be updated via an
     * HTTP request for a given integer ID. First, all GTFS tables are filtered to find referencing tables. Then records
     * in these tables that match the old key value are modified to match the new key value.
     *
     * The function determines whether the method is update or delete depending on the presence of the newKeyValue
     * parameter (if null, the method is DELETE). Custom logic/hooks could be added here to check if there are entities
     * referencing the entity being updated.
     *
     * FIXME: add custom logic/hooks. Right now entity table checks are hard-coded in (e.g., if Agency, skip all. OR if
     * Calendar, rollback transaction if there are referencing trips).
     *
     * FIXME: Do we need to clarify the impact of the direction of the relationship (e.g., if we delete a trip, that should
     * not necessarily delete a shape that is shared by multiple trips)? I think not because we are skipping foreign refs
     * found in the table for the entity being updated/deleted. [Leaving this comment in place for now though.]
     */
    private static void updateReferencingTables(String namespace, Table table, Connection connection, int id, String newKeyValue) throws SQLException {
        String keyField = table.getKeyFieldName();
        Class<? extends Entity> entityClass = table.getEntityClass();
        // Determine method (update vs. delete) depending on presence of newKeyValue field.
        SqlMethod sqlMethod = newKeyValue != null ? SqlMethod.UPDATE : SqlMethod.DELETE;
        Set<Table> referencingTables = getReferencingTables(table);
        // If there are no referencing tables, there is no need to update any values (e.g., .
        if (referencingTables.size() == 0) return;
        String keyValue = getValueForId(id, keyField, namespace, table, connection);
        if (keyValue == null) {
            // FIXME: should we still check referencing tables for null value?
            LOG.warn("Entity {} to {} has null value for {}. Skipping references check.", id, sqlMethod, keyField);
            return;
        }
        for (Table referencingTable : referencingTables) {
            // Update/delete foreign references that have match the key value.
            String refTableName = String.join(".", namespace, referencingTable.name);
            // Get unique IDs before delete (for logging/message purposes).
//            TIntSet uniqueIds = getIdsForCondition(refTableName, keyField, keyValue, connection);
            String updateRefSql = getUpdateReferencesSql(sqlMethod, refTableName, keyField, keyValue, newKeyValue);
            LOG.info(updateRefSql);
            Statement updateStatement = connection.createStatement();
            int result = updateStatement.executeUpdate(updateRefSql);
            if (result > 0) {
                // FIXME: is this where a delete hook should go? (E.g., CalendarController subclass would override
                // deleteEntityHook).
//                    deleteEntityHook();
                if (sqlMethod.equals(SqlMethod.DELETE)) {
                    // Check for restrictions on delete.
                    if (table.isDeleteRestricted()) {
                        // The entity must not have any referencing entities in order to delete it.
                        connection.rollback();
//                        List<String> idStrings = new ArrayList<>();
//                        uniqueIds.forEach(uniqueId -> idStrings.add(String.valueOf(uniqueId)));
//                        String message = String.format("Cannot delete %s %s=%s. %d %s reference this %s (%s).", entityClass.getSimpleName(), keyField, keyValue, result, referencingTable.name, entityClass.getSimpleName(), String.join(",", idStrings));
                        String message = String.format("Cannot delete %s %s=%s. %d %s reference this %s.", entityClass.getSimpleName(), keyField, keyValue, result, referencingTable.name, entityClass.getSimpleName());
                        LOG.warn(message);
                        throw new SQLException(message);
                    }
                }
                LOG.info("{} reference(s) in {} {}D!", result, refTableName, sqlMethod);
            } else {
                LOG.info("No references in {} found!", refTableName);
            }
        }
    }

    /**
     * Constructs SQL string based on method provided.
     */
    private static String getUpdateReferencesSql(SqlMethod sqlMethod, String refTableName, String keyField, String keyValue, String newKeyValue) throws SQLException {
        switch (sqlMethod) {
            case DELETE:
                return String.format("delete from %s where %s = '%s'", refTableName, keyField, keyValue);
            case UPDATE:
                return String.format("update %s set %s = '%s' where %s = '%s'", refTableName, keyField, newKeyValue, keyField, keyValue);
//            case CREATE:
//                return String.format("insert into %s ");
            default:
                throw new SQLException("SQL Method must be DELETE or UPDATE.");
        }
    }
}