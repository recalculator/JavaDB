package com.javadb.catalog;

import java.io.*;
import java.util.*;

/**
 * Stores table schemas and named index metadata, persisted to a flat text file.
 *
 * File format (catalog.cat):
 *   TABLE <tableName>
 *   COLUMN <colName> <INT|STRING>
 *   INDEX <indexName> <tableName> <colName>
 *
 * The file is rewritten in full on every schema change.
 */
public class Catalog {

    private static final String CATALOG_FILE = "catalog.cat";

    private final Map<String, TableSchema> tables = new LinkedHashMap<>();
    // key: "tableName.columnName" (both lower-cased) → IndexMetadata
    private final Map<String, IndexMetadata> indexes = new LinkedHashMap<>();

    private final File catalogFile;

    public Catalog(File dataDir) throws IOException {
        this.catalogFile = new File(dataDir, CATALOG_FILE);
        if (catalogFile.exists()) {
            load();
        }
    }

    // ── Schema operations ──────────────────────────────────────────────────────

    public void createTable(TableSchema schema) throws IOException {
        String key = schema.tableName().toLowerCase();
        if (tables.containsKey(key)) {
            throw new IllegalStateException("Table already exists: " + schema.tableName());
        }
        tables.put(key, schema);
        persist();
    }

    /**
     * Registers a named index. Validates that table and column exist and that
     * no index on the same table+column already exists.
     */
    public void registerIndex(String indexName, String tableName, String columnName) throws IOException {
        String tableKey = tableName.toLowerCase();
        if (!tables.containsKey(tableKey)) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        TableSchema schema = tables.get(tableKey);
        Column col;
        try {
            col = schema.column(columnName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Column not found: " + columnName + " in table " + tableName);
        }
        if (col.type() != DataType.INT) {
            throw new IllegalArgumentException(
                "Cannot create index on column '" + columnName + "': only INT columns are supported");
        }
        String idxKey = indexKey(tableName, columnName);
        if (indexes.containsKey(idxKey)) {
            throw new IllegalStateException(
                "Index already exists on " + tableName + "(" + columnName + ")");
        }
        indexes.put(idxKey, new IndexMetadata(indexName, tableName, columnName));
        persist();
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

    public List<IndexMetadata> getAllIndexes() {
        return List.copyOf(indexes.values());
    }

    /** Returns all indexes for the given table. */
    public List<IndexMetadata> indexesFor(String tableName) {
        String prefix = tableName.toLowerCase() + ".";
        List<IndexMetadata> result = new ArrayList<>();
        for (Map.Entry<String, IndexMetadata> e : indexes.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                result.add(e.getValue());
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns all indexed column names for a table (used by planner/executor). */
    public List<String> indexedColumnsFor(String tableName) {
        return indexesFor(tableName).stream()
            .map(IndexMetadata::columnName)
            .toList();
    }

    /** Returns the IndexMetadata for a specific table+column, or empty. */
    public Optional<IndexMetadata> findIndex(String tableName, String columnName) {
        return Optional.ofNullable(indexes.get(indexKey(tableName, columnName)));
    }

    public void dropTable(String name) throws IOException {
        String key = name.toLowerCase();
        tables.remove(key);
        // Remove all indexes for this table.
        indexes.entrySet().removeIf(e -> e.getKey().startsWith(key + "."));
        persist();
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private void persist() throws IOException {
        File tmp = new File(catalogFile.getParent(), CATALOG_FILE + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tmp))) {
            for (Map.Entry<String, TableSchema> entry : tables.entrySet()) {
                TableSchema schema = entry.getValue();
                w.write("TABLE " + schema.tableName());
                w.newLine();
                for (Column col : schema.columns()) {
                    w.write("COLUMN " + col.name() + " " + col.type().name());
                    w.newLine();
                }
            }
            // Write all indexes after all tables.
            for (IndexMetadata idx : indexes.values()) {
                w.write("INDEX " + idx.indexName() + " " + idx.tableName() + " " + idx.columnName());
                w.newLine();
            }
        }
        if (!tmp.renameTo(catalogFile)) {
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = new FileOutputStream(catalogFile)) {
                in.transferTo(out);
            }
            tmp.delete();
        }
    }

    private void load() throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(catalogFile))) {
            String line;
            String currentTable = null;
            List<Column> currentColumns = new ArrayList<>();

            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("TABLE ")) {
                    if (currentTable != null) {
                        TableSchema schema = new TableSchema(currentTable, List.copyOf(currentColumns));
                        tables.put(currentTable.toLowerCase(), schema);
                    }
                    currentTable = line.substring(6).trim();
                    currentColumns = new ArrayList<>();

                } else if (line.startsWith("COLUMN ")) {
                    String[] parts = line.substring(7).trim().split("\\s+", 2);
                    currentColumns.add(new Column(parts[0], DataType.valueOf(parts[1])));

                } else if (line.startsWith("INDEX ")) {
                    // INDEX <indexName> <tableName> <columnName>
                    String[] parts = line.substring(6).trim().split("\\s+", 3);
                    if (parts.length == 3) {
                        String idxName = parts[0];
                        String tbl     = parts[1];
                        String col     = parts[2];
                        indexes.put(indexKey(tbl, col), new IndexMetadata(idxName, tbl, col));
                    }
                }
            }
            if (currentTable != null) {
                tables.put(currentTable.toLowerCase(), new TableSchema(currentTable, List.copyOf(currentColumns)));
            }
        }
    }

    private static String indexKey(String table, String column) {
        return table.toLowerCase() + "." + column.toLowerCase();
    }
}
