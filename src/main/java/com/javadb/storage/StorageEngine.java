package com.javadb.storage;

import com.javadb.catalog.TableSchema;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class StorageEngine implements AutoCloseable {

    private final File dataDir;
    private final Map<String, TableFile> openFiles = new HashMap<>();

    public StorageEngine(File dataDir) throws IOException {
        this.dataDir = dataDir;
        if (!dataDir.exists()) dataDir.mkdirs();
    }

    public void createTable(TableSchema schema) throws IOException {
        File f = tableFile(schema.tableName());
        if (f.exists()) throw new IOException("Table file already exists: " + f);
        f.createNewFile();
    }

    public TableFile openTable(TableSchema schema) throws IOException {
        String key = schema.tableName().toLowerCase();
        if (!openFiles.containsKey(key)) {
            openFiles.put(key, new TableFile(tableFile(schema.tableName()), schema));
        }
        return openFiles.get(key);
    }

    public void dropTable(String name) throws IOException {
        String key = name.toLowerCase();
        TableFile tf = openFiles.remove(key);
        if (tf != null) tf.close();
        tableFile(name).delete();
    }

    private File tableFile(String name) {
        return new File(dataDir, name.toLowerCase() + ".tbl");
    }

    @Override
    public void close() throws IOException {
        for (TableFile tf : openFiles.values()) tf.close();
        openFiles.clear();
    }
}
