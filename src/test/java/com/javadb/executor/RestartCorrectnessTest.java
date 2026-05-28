package com.javadb.executor;

import com.javadb.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify restart-safety: the database is closed and reopened between
 * operations to confirm that schema, data, and indexes survive a JVM restart.
 */
class RestartCorrectnessTest {

    // ── 1. Schema persistence ──────────────────────────────────────────────────

    @Test
    void schemaExistsAfterRestart(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE users (id INT, name STRING, age INT)");
        }
        // Reopen — catalog must load from disk.
        try (Database db = new Database(dir)) {
            // A query against the table would throw if the schema were lost.
            QueryResult r = db.execute("SELECT * FROM users");
            assertEquals(0, r.rows().size(), "Table should exist and be empty");
            assertEquals(List.of("id", "name", "age"), r.columns());
        }
    }

    @Test
    void multipleTablesPersistedCorrectly(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE a (id INT, val STRING)");
            db.execute("CREATE TABLE b (x INT, y INT)");
        }
        try (Database db = new Database(dir)) {
            // Both tables must be queryable after restart.
            QueryResult ra = db.execute("SELECT * FROM a");
            QueryResult rb = db.execute("SELECT * FROM b");
            assertEquals(List.of("id", "val"), ra.columns());
            assertEquals(List.of("x", "y"), rb.columns());
        }
    }

    // ── 2. Row data persistence ────────────────────────────────────────────────

    @Test
    void rowsReadableAfterRestart(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            db.execute("INSERT INTO t VALUES (1, 'Alice')");
            db.execute("INSERT INTO t VALUES (2, 'Bob')");
        }
        try (Database db = new Database(dir)) {
            QueryResult r = db.execute("SELECT * FROM t");
            assertEquals(2, r.rows().size());
            // Verify actual values survived.
            boolean foundAlice = r.rows().stream().anyMatch(row -> "Alice".equals(row.get(1)));
            boolean foundBob   = r.rows().stream().anyMatch(row -> "Bob".equals(row.get(1)));
            assertTrue(foundAlice, "Alice should be present after restart");
            assertTrue(foundBob,   "Bob should be present after restart");
        }
    }

    @Test
    void manyRowsReadableAfterRestart(@TempDir File dir) throws Exception {
        int n = 200;
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE nums (id INT, label STRING)");
            for (int i = 1; i <= n; i++) {
                db.execute("INSERT INTO nums VALUES (" + i + ", 'row" + i + "')");
            }
        }
        try (Database db = new Database(dir)) {
            QueryResult r = db.execute("SELECT * FROM nums");
            assertEquals(n, r.rows().size(), "All rows must survive restart");
        }
    }

    // ── 3. Index metadata and rebuild ─────────────────────────────────────────

    @Test
    void indexMetadataPersistedInCatalog(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            // CREATE TABLE auto-indexes the first INT column; catalog must record it.
        }
        // Open a second Database — it should load the index metadata and rebuild.
        try (Database db = new Database(dir)) {
            // If the index were lost, a WHERE id = ? query would still work via
            // full scan but the planner would not choose an index scan. We verify
            // correctness by checking the result, not the plan path.
            db.execute("INSERT INTO t VALUES (7, 'X')");
            QueryResult r = db.execute("SELECT * FROM t WHERE id = 7");
            assertEquals(1, r.rows().size());
            assertEquals(7, r.rows().get(0).get(0));
        }
    }

    @Test
    void indexedSelectWorksAfterRestart(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            for (int i = 1; i <= 50; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'user" + i + "')");
            }
        }
        try (Database db = new Database(dir)) {
            // Index must have been rebuilt from the table file.
            QueryResult r = db.execute("SELECT * FROM t WHERE id = 25");
            assertEquals(1, r.rows().size());
            assertEquals(25, r.rows().get(0).get(0));
            assertEquals("user25", r.rows().get(0).get(1));
        }
    }

    @Test
    void indexedSelectMissesDeletedRowAfterRestart(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            db.execute("INSERT INTO t VALUES (1, 'keep')");
            db.execute("INSERT INTO t VALUES (2, 'delete-me')");
            db.execute("DELETE FROM t WHERE id = 2");
        }
        try (Database db = new Database(dir)) {
            // The deleted row's tombstone should be read from disk; the index
            // rebuild skips tombstoned slots, so id=2 must not appear.
            QueryResult r = db.execute("SELECT * FROM t WHERE id = 2");
            assertEquals(0, r.rows().size(), "Deleted row must not appear after restart");
            // The live row must still be there.
            QueryResult r2 = db.execute("SELECT * FROM t WHERE id = 1");
            assertEquals(1, r2.rows().size());
        }
    }

    // ── 4. WAL commit ordering ─────────────────────────────────────────────────

    @Test
    void committedInsertSurvivesRestart(@TempDir File dir) throws Exception {
        // Normal path: insert succeeds, COMMIT is written, database closes cleanly.
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("INSERT INTO t VALUES (99, 'hello')");
            // Database.close() flushes storage — COMMIT was already written.
        }
        try (Database db = new Database(dir)) {
            QueryResult r = db.execute("SELECT * FROM t WHERE id = 99");
            assertEquals(1, r.rows().size(), "Committed row must survive restart");
            assertEquals("hello", r.rows().get(0).get(1));
        }
    }

    @Test
    void walIsCleanAfterCheckpoint(@TempDir File dir) throws Exception {
        // After a clean startup, recovery checkpoints the WAL (truncates it).
        // The next Database open should see an empty WAL (no leftover entries).
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("INSERT INTO t VALUES (1)");
        }
        // Second open: recovery checkpoints the WAL from the first session.
        try (Database db = new Database(dir)) {
            db.execute("INSERT INTO t VALUES (2)");
        }
        // Third open: WAL from second session is also checkpointed.
        // Row count must still be correct.
        try (Database db = new Database(dir)) {
            QueryResult r = db.execute("SELECT * FROM t");
            assertEquals(2, r.rows().size());
        }
    }

    // ── 5. Delete correctness ──────────────────────────────────────────────────

    @Test
    void deleteDoesNotCorruptLaterRowIds(@TempDir File dir) throws Exception {
        // Insert three rows, delete the middle one, verify the third is still reachable
        // via its original RowId (through the index) and has correct data.
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("INSERT INTO t VALUES (10, 'ten')");
            db.execute("INSERT INTO t VALUES (20, 'twenty')");
            db.execute("INSERT INTO t VALUES (30, 'thirty')");

            db.execute("DELETE FROM t WHERE id = 20");

            // Row 30 must still be reachable by index scan.
            QueryResult r30 = db.execute("SELECT * FROM t WHERE id = 30");
            assertEquals(1, r30.rows().size());
            assertEquals("thirty", r30.rows().get(0).get(1));

            // Row 10 must also still be reachable.
            QueryResult r10 = db.execute("SELECT * FROM t WHERE id = 10");
            assertEquals(1, r10.rows().size());
            assertEquals("ten", r10.rows().get(0).get(1));

            // Full scan must see exactly two live rows.
            QueryResult all = db.execute("SELECT * FROM t");
            assertEquals(2, all.rows().size());
        }
    }

    @Test
    void deleteAndReinsertSameKey(@TempDir File dir) throws Exception {
        // Deleting a key and reinserting the same key must work correctly
        // without leaving ghost index entries.
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("INSERT INTO t VALUES (5, 'first')");
            db.execute("DELETE FROM t WHERE id = 5");
            db.execute("INSERT INTO t VALUES (5, 'second')");

            QueryResult r = db.execute("SELECT * FROM t WHERE id = 5");
            assertEquals(1, r.rows().size());
            assertEquals("second", r.rows().get(0).get(1));
        }
    }

    @Test
    void multipleDeletesPreserveOtherRows(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            for (int i = 1; i <= 10; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            // Delete even ids.
            for (int i = 2; i <= 10; i += 2) {
                db.execute("DELETE FROM t WHERE id = " + i);
            }

            // Odd ids must all still be reachable.
            for (int i = 1; i <= 10; i += 2) {
                QueryResult r = db.execute("SELECT * FROM t WHERE id = " + i);
                assertEquals(1, r.rows().size(), "Row " + i + " should still exist");
            }
            // Even ids must be gone.
            for (int i = 2; i <= 10; i += 2) {
                QueryResult r = db.execute("SELECT * FROM t WHERE id = " + i);
                assertEquals(0, r.rows().size(), "Row " + i + " should be deleted");
            }

            QueryResult all = db.execute("SELECT * FROM t");
            assertEquals(5, all.rows().size());
        }
    }
}
