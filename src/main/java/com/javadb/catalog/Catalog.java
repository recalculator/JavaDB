package com.javadb.catalog;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Catalog {
    private final Map<String, TableSchema> tables = new HashMap<>();

    public void createTable(TableSchema schema) {
        String key = schema.tableName().toLowerCase();
        if (tables.containsKey(key)) {
            throw new IllegalStateException("Table already exists: " + schema.tableName());
        }
        tables.put(key, schema);
    }

    public TableSchema getTable(String name) {
        TableSchema schema = tables.get(name.toLowerCase());
        if (schema == null) {
            throw new IllegalArgumentException("Table not found: " + name);
        }
        return schema;
    }

    public boolean tableExists(String name) {
        return tables.containsKey(name.toLowerCase());
    }

    public Map<String, TableSchema> getAllTables() {
        return Collections.unmodifiableMap(tables);
    }

    public void dropTable(String name) {
        tables.remove(name.toLowerCase());
    }
}
