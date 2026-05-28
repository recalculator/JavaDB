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
    void tombstonePreservesSlotIndex(@TempDir File tmpDir) throws Exception {
        // Tombstoning row 0 must not shift row 1's slot index.
        File f = new File(tmpDir, "users.tbl");
        f.createNewFile();
        TableFile tf = new TableFile(f, SCHEMA);
        RowId rid0 = tf.insertRow(Row.of(1, "A"));
        RowId rid1 = tf.insertRow(Row.of(2, "B"));

        tf.tombstoneRow(rid0);

        // scanAll should skip the tombstone and return only row 1.
        List<Row> live = tf.scanAll();
        assertEquals(1, live.size());
        assertEquals(2, live.get(0).get(0));

        // Row 1's RowId must still be valid after the tombstone.
        Row row1 = tf.getRow(rid1);
        assertFalse(row1.deleted());
        assertEquals(2, row1.get(0));

        tf.close();
    }

    @Test
    void tombstonePersistsAcrossReopen(@TempDir File tmpDir) throws Exception {
        // After closing and reopening the file, tombstoned rows must still be tombstoned.
        File f = new File(tmpDir, "users.tbl");
        f.createNewFile();
        RowId rid0;
        try (TableFile tf = new TableFile(f, SCHEMA)) {
            rid0 = tf.insertRow(Row.of(1, "A"));
            tf.insertRow(Row.of(2, "B"));
            tf.tombstoneRow(rid0);
        }
        try (TableFile tf = new TableFile(f, SCHEMA)) {
            assertTrue(tf.getRow(rid0).deleted(), "Tombstone should survive a close/reopen");
            List<Row> live = tf.scanAll();
            assertEquals(1, live.size());
            assertEquals(2, live.get(0).get(0));
        }
    }

    @Test
    void scanAllRowIdsSkipsTombstones(@TempDir File tmpDir) throws Exception {
        File f = new File(tmpDir, "users.tbl");
        f.createNewFile();
        TableFile tf = new TableFile(f, SCHEMA);
        RowId r0 = tf.insertRow(Row.of(1, "A"));
        RowId r1 = tf.insertRow(Row.of(2, "B"));
        RowId r2 = tf.insertRow(Row.of(3, "C"));
        tf.tombstoneRow(r1);

        List<RowId> rids = tf.scanAllRowIds();
        assertEquals(2, rids.size());
        assertTrue(rids.contains(r0));
        assertTrue(rids.contains(r2));
        assertFalse(rids.contains(r1));
        tf.close();
    }
}
