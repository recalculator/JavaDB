package com.javadb.storage;

import com.javadb.catalog.Column;
import com.javadb.catalog.DataType;
import com.javadb.catalog.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {

    private static final TableSchema SCHEMA = new TableSchema("users",
        List.of(new Column("id", DataType.INT), new Column("name", DataType.STRING)));

    @Test
    void insertAndScanRow(@TempDir File tmpDir) throws Exception {
        File f = new File(tmpDir, "users.tbl");
        f.createNewFile();
        TableFile tf = new TableFile(f, SCHEMA);
        Row row = Row.of(1, "Ayaan");
        tf.insertRow(row);
        List<Row> all = tf.scanAll();
        assertEquals(1, all.size());
        assertEquals(1, all.get(0).get(0));
        assertEquals("Ayaan", all.get(0).get(1));
        tf.close();
    }

    @Test
    void persistsAcrossReopen(@TempDir File tmpDir) throws Exception {
        File f = new File(tmpDir, "users.tbl");
        f.createNewFile();
        try (TableFile tf = new TableFile(f, SCHEMA)) {
            tf.insertRow(Row.of(42, "Persisted"));
        }
        try (TableFile tf = new TableFile(f, SCHEMA)) {
            List<Row> rows = tf.scanAll();
            assertEquals(1, rows.size());
            assertEquals(42, rows.get(0).get(0));
        }
    }

    @Test
    void updateRow(@TempDir File tmpDir) throws Exception {
        File f = new File(tmpDir, "users.tbl");
        f.createNewFile();
        TableFile tf = new TableFile(f, SCHEMA);
        RowId rid = tf.insertRow(Row.of(1, "Old"));
        tf.updateRow(rid, Row.of(1, "New"));
        assertEquals("New", tf.getRow(rid).get(1));
        tf.close();
    }

    @Test
    void deleteRow(@TempDir File tmpDir) throws Exception {
        File f = new File(tmpDir, "users.tbl");
        f.createNewFile();
        TableFile tf = new TableFile(f, SCHEMA);
        tf.insertRow(Row.of(1, "A"));
        RowId rid2 = tf.insertRow(Row.of(2, "B"));
        // delete slot 0 — then recount
        tf.deleteRow(new RowId(0, 0));
        assertEquals(1, tf.scanAll().size());
        tf.close();
    }
}
