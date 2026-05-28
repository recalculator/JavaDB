package com.javadb.executor;

import com.javadb.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CREATE INDEX: parsing, validation, backfill, restart persistence,
 * and planner integration.
 */
class CreateIndexTest {

    // ── Parser ─────────────────────────────────────────────────────────────────

    @Test
    void parserParsesCreateIndex() {
        var stmt = (com.javadb.parser.ast.CreateIndexStatement)
            com.javadb.parser.Parser.parse("CREATE INDEX idx_users_id ON users(id)");
        assertEquals("idx_users_id", stmt.indexName());
        assertEquals("users", stmt.tableName());
        assertEquals("id", stmt.columnName());
    }

    // ── Validation ─────────────────────────────────────────────────────────────

    @Test
    void createIndexOnMissingTableFails(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            var ex = assertThrows(Exception.class, () ->
                db.execute("CREATE INDEX idx ON ghost(id)"));
            assertTrue(ex.getMessage().toLowerCase().contains("table"),
                "Error should mention table, got: " + ex.getMessage());
        }
    }

    @Test
    void createIndexOnMissingColumnFails(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            var ex = assertThrows(Exception.class, () ->
                db.execute("CREATE INDEX idx ON t(nonexistent)"));
            assertTrue(ex.getMessage().toLowerCase().contains("column"),
                "Error should mention column, got: " + ex.getMessage());
        }
    }

    @Test
    void createIndexOnStringColumnFails(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            var ex = assertThrows(Exception.class, () ->
                db.execute("CREATE INDEX idx ON t(name)"));
            assertTrue(ex.getMessage().toLowerCase().contains("int"),
                "Error should mention INT restriction, got: " + ex.getMessage());
        }
    }

    @Test
    void duplicateIndexOnSameColumnFails(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("CREATE INDEX idx1 ON t(id)");
            assertThrows(Exception.class, () ->
                db.execute("CREATE INDEX idx2 ON t(id)"));
        }
    }

    // ── Backfill ───────────────────────────────────────────────────────────────

    @Test
    void createIndexBackfillsExistingRows(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            for (int i = 1; i <= 10; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            db.execute("CREATE INDEX idx_t_id ON t(id)");

            // After backfill, index lookup should return exactly one row.
            var r = db.execute("SELECT * FROM t WHERE id = 5");
            assertEquals(1, r.rows().size(), "Index lookup should return 1 row");
            assertEquals(5, r.rows().get(0).get(0));

            // Range scan should work too.
            var range = db.execute("SELECT * FROM t WHERE id BETWEEN 3 AND 7");
            assertEquals(5, range.rows().size(), "Range [3,7] should return 5 rows");
        }
    }

    @Test
    void createIndexOnEmptyTableThenInsert(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            // Insert after index creation
            db.execute("INSERT INTO t VALUES (42, 'hello')");
            var r = db.execute("SELECT * FROM t WHERE id = 42");
            assertEquals(1, r.rows().size());
        }
    }

    // ── Restart persistence ────────────────────────────────────────────────────

    @Test
    void indexMetadataSurvivesRestart(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            for (int i = 1; i <= 5; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v')");
            }
            db.execute("CREATE INDEX idx_t_id ON t(id)");
        }
        // Reopen — index should be rebuilt from catalog metadata
        try (Database db = new Database(dir)) {
            // EXPLAIN should show INDEX_SCAN after restart
            var exp = db.execute("EXPLAIN SELECT * FROM t WHERE id = 3");
            assertTrue(exp.message().contains("INDEX_SCAN"),
                "After restart, EXPLAIN should show INDEX_SCAN, got: " + exp.message());
            assertTrue(exp.message().contains("idx_t_id"),
                "Index name should survive restart");

            // Actual lookup should work
            var r = db.execute("SELECT * FROM t WHERE id = 3");
            assertEquals(1, r.rows().size());
            assertEquals(3, r.rows().get(0).get(0));
        }
    }

    // ── Planner integration ────────────────────────────────────────────────────

    @Test
    void plannerUsesFullScanBeforeIndex(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            var r = db.execute("EXPLAIN SELECT * FROM t WHERE id = 1");
            assertTrue(r.message().contains("FULL_SCAN"),
                "No index yet — should be FULL_SCAN, got: " + r.message());
        }
    }

    @Test
    void plannerUsesIndexScanAfterCreateIndex(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("CREATE INDEX idx ON t(id)");
            var r = db.execute("EXPLAIN SELECT * FROM t WHERE id = 1");
            assertTrue(r.message().contains("INDEX_SCAN"),
                "After CREATE INDEX, should be INDEX_SCAN, got: " + r.message());
        }
    }

    @Test
    void plannerUsesIndexRangeScanForRange(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("CREATE INDEX idx ON t(id)");
            for (String pred : new String[]{"id > 5", "id >= 5", "id < 10", "id <= 10", "id BETWEEN 3 AND 8"}) {
                var r = db.execute("EXPLAIN SELECT * FROM t WHERE " + pred);
                assertTrue(r.message().contains("INDEX_RANGE_SCAN"),
                    "Predicate [" + pred + "] should use INDEX_RANGE_SCAN, got: " + r.message());
            }
        }
    }

    @Test
    void indexedQueryReturnsCorrectResults(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE users (id INT, name STRING, age INT)");
            db.execute("CREATE INDEX idx_users_id ON users(id)");
            for (int i = 1; i <= 100; i++) {
                db.execute("INSERT INTO users VALUES (" + i + ", 'user" + i + "', " + (20 + i % 30) + ")");
            }
            // Point lookup
            var r1 = db.execute("SELECT * FROM users WHERE id = 42");
            assertEquals(1, r1.rows().size());
            assertEquals(42, r1.rows().get(0).get(0));

            // Range
            var r2 = db.execute("SELECT * FROM users WHERE id BETWEEN 90 AND 100");
            assertEquals(11, r2.rows().size());

            // After delete, index lookup should return empty
            db.execute("DELETE FROM users WHERE id = 42");
            var r3 = db.execute("SELECT * FROM users WHERE id = 42");
            assertEquals(0, r3.rows().size());
        }
    }
}
