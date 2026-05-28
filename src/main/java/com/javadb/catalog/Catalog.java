package com.javadb.catalog;

import java.io.*;
import java.util.*;

/**
 * Stores table schemas and index metadata, persisted to a flat text file.
 *
 * File format (catalog.cat):
 *   TABLE <tableName>
 *   COLUMN <colName> <INT|STRING>
 *   COLUMN <colName> <INT|STRING>
 *   INDEX <tableName> <colName>
 *   TABLE <tableName2>
 *   ...
 *
 * One TABLE block per table; INDEX lines follow after all COLUMN lines for that table.
 * The file is rewritten in full on every schema change — schemas change rarely, so
 * the simplicity is worth it over a more complex append-only format.
 */
public class Catalog {

    private static final String CATALOG_FILE = "catalog.cat";

    private final Map<String, TableSchema> tables = new LinkedHashMap<>();
    // index metadata: tableName.toLowerCase() -> list of indexed column names
    private final Map<String, List<String>> indexedColumns = new LinkedHashMap<>();

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
        indexedColumns.put(key, new ArrayList<>());
        persist();
    }

    public void registerIndex(String tableName, String columnName) throws IOException {
        String key = tableName.toLowerCase();
        if (!tables.containsKey(key)) {
            throw new IllegalArgumentException("Table not found: " + tableName);
        }
        List<String> cols = indexedColumns.computeIfAbsent(key, k -> new ArrayList<>());
        if (!cols.contains(columnName.toLowerCase())) {
            cols.add(columnName.toLowerCase());
        }
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
        List<IndexMetadata> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : indexedColumns.entrySet()) {
            String tableName = tables.get(entry.getKey()).tableName();
            for (String col : entry.getValue()) {
                result.add(new IndexMetadata(tableName, col));
            }
        }
        return result;
    }

    public List<String> indexedColumnsFor(String tableName) {
        return Collections.unmodifiableList(
            indexedColumns.getOrDefault(tableName.toLowerCase(), List.of()));
    }

    public void dropTable(String name) throws IOException {
        String key = name.toLowerCase();
        tables.remove(key);
        indexedColumns.remove(key);
        persist();
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    /**
     * Rewrites the catalog file atomically using a temp file + rename.
     * This prevents a partial write from corrupting the catalog.
     */
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
                List<String> idxCols = indexedColumns.getOrDefault(entry.getKey(), List.of());
                for (String colName : idxCols) {
                    w.write("INDEX " + schema.tableName() + " " + colName);
                    w.newLine();
                }
            }
        }
        // Atomic rename: either the full new file is visible or the old one is.
        if (!tmp.renameTo(catalogFile)) {
            // renameTo can fail across filesystems; fall back to copy+delete.
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
                    // Flush previous table if any
                    if (currentTable != null) {
                        TableSchema schema = new TableSchema(currentTable, List.copyOf(currentColumns));
                        tables.put(currentTable.toLowerCase(), schema);
                    }
                    currentTable = line.substring(6).trim();
                    currentColumns = new ArrayList<>();
                    indexedColumns.putIfAbsent(currentTable.toLowerCase(), new ArrayList<>());

                } else if (line.startsWith("COLUMN ")) {
                    String[] parts = line.substring(7).trim().split("\\s+", 2);
                    String colName = parts[0];
                    DataType type = DataType.valueOf(parts[1]);
                    currentColumns.add(new Column(colName, type));

                } else if (line.startsWith("INDEX ")) {
                    // INDEX <tableName> <columnName>
                    String[] parts = line.substring(6).trim().split("\\s+", 2);
                    String tbl = parts[0];
                    String col = parts[1];
                    indexedColumns
                        .computeIfAbsent(tbl.toLowerCase(), k -> new ArrayList<>())
                        .add(col.toLowerCase());
                }
            }
            // Flush the last table
            if (currentTable != null) {
                TableSchema schema = new TableSchema(currentTable, List.copyOf(currentColumns));
                tables.put(currentTable.toLowerCase(), schema);
            }
        }
    }
}
