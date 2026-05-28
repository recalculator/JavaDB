package com.javadb.catalog;

import java.util.List;

public record TableSchema(String tableName, List<Column> columns) {

    public int columnIndex(String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equalsIgnoreCase(name)) return i;
        }
        throw new IllegalArgumentException("Unknown column: " + name);
    }

    public Column column(String name) {
        return columns.get(columnIndex(name));
    }
}
