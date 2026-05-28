package com.javadb.planner;

import com.javadb.Database;
import com.javadb.executor.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that EXPLAIN returns the correct plan type and that the planner
 * chooses INDEX_SCAN / INDEX_RANGE_SCAN when an explicit CREATE INDEX exists
 * and FULL_SCAN otherwise.
 */
class ExplainTest {

    // ── EXPLAIN plan types ─────────────────────────────────────────────────────

    @Test
    void explainEqualityUsesIndexScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id = 42");
            assertNotNull(r.message());
            assertTrue(r.message().contains("INDEX_SCAN"),
                "Expected INDEX_SCAN, got: " + r.message());
            assertTrue(r.message().contains("id"));
            assertTrue(r.message().contains("42"));
        }
    }

    @Test
    void explainShowsIndexName(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id = 5");
            assertTrue(r.message().contains("idx_t_id"),
                "EXPLAIN should show index name, got: " + r.message());
        }
    }

    @Test
    void explainBeforeCreateIndexUsesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            // No CREATE INDEX yet — must fall back to full scan even on indexed-type col
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id = 42");
            assertTrue(r.message().contains("FULL_SCAN"),
                "Before CREATE INDEX, planner should use FULL_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainAfterCreateIndexUsesIndexScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            // Before index
            QueryResult before = db.execute("EXPLAIN SELECT * FROM t WHERE id = 1");
            assertTrue(before.message().contains("FULL_SCAN"),
                "Before index: " + before.message());
            // Create index
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            // After index
            QueryResult after = db.execute("EXPLAIN SELECT * FROM t WHERE id = 1");
            assertTrue(after.message().contains("INDEX_SCAN"),
                "After index: " + after.message());
        }
    }

    @Test
    void explainNoIndexUsesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE name = 'alice'");
            assertTrue(r.message().contains("FULL_SCAN"),
                "Expected FULL_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainNoWhereUsesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t");
            assertTrue(r.message().contains("FULL_SCAN"));
        }
    }

    @Test
    void explainGtUsesIndexRangeScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id > 10");
            assertTrue(r.message().contains("INDEX_RANGE_SCAN"),
                "Expected INDEX_RANGE_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainLtUsesIndexRangeScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id < 100");
            assertTrue(r.message().contains("INDEX_RANGE_SCAN"));
        }
    }

    @Test
    void explainBetweenUsesIndexRangeScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id BETWEEN 10 AND 20");
            assertTrue(r.message().contains("INDEX_RANGE_SCAN"),
                "Expected INDEX_RANGE_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainRangeOnNonIndexedColumnUsesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            // age has no index; only id has one
            db.execute("CREATE TABLE t (id INT, age INT, name STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE age > 18");
            assertTrue(r.message().contains("FULL_SCAN"),
                "Non-indexed column should fall back to FULL_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainOutputContainsTableName(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE employees (id INT, dept STRING)");
            db.execute("CREATE INDEX idx_emp_id ON employees(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM employees WHERE id = 1");
            assertTrue(r.message().contains("employees"),
                "EXPLAIN output should contain table name");
        }
    }

    @Test
    void explainRangeShowsIndexName(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("CREATE INDEX my_index ON t(id)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id BETWEEN 1 AND 100");
            assertTrue(r.message().contains("my_index"),
                "Range scan EXPLAIN should show index name, got: " + r.message());
        }
    }

    // ── Range scan correctness ─────────────────────────────────────────────────

    @Test
    void rangeGtMatchesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            for (int i = 1; i <= 20; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            QueryResult byIndex = db.execute("SELECT * FROM t WHERE id > 15");
            assertEquals(5, byIndex.rows().size(),
                "id > 15 should return 5 rows (16-20)");
            for (var row : byIndex.rows()) {
                assertTrue((Integer) row.get(0) > 15,
                    "All returned ids must be > 15, got " + row.get(0));
            }
        }
    }

    @Test
    void rangeLteMatchesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            for (int i = 1; i <= 10; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            QueryResult r = db.execute("SELECT * FROM t WHERE id <= 5");
            assertEquals(5, r.rows().size());
            for (var row : r.rows()) {
                assertTrue((Integer) row.get(0) <= 5);
            }
        }
    }

    @Test
    void rangeBetweenMatchesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            for (int i = 1; i <= 30; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            QueryResult r = db.execute("SELECT * FROM t WHERE id BETWEEN 10 AND 20");
            assertEquals(11, r.rows().size(), "BETWEEN 10 AND 20 should return 11 rows");
            for (var row : r.rows()) {
                int id = (Integer) row.get(0);
                assertTrue(id >= 10 && id <= 20, "id " + id + " out of range [10,20]");
            }
        }
    }

    @Test
    void rangeGteMatchesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            for (int i = 1; i <= 10; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            QueryResult r = db.execute("SELECT * FROM t WHERE id >= 7");
            assertEquals(4, r.rows().size(), "id >= 7 should return 4 rows (7,8,9,10)");
        }
    }

    @Test
    void rangeEmptyResultWhenNoneMatch(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            db.execute("CREATE INDEX idx_t_id ON t(id)");
            for (int i = 1; i <= 5; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v')");
            }
            QueryResult r = db.execute("SELECT * FROM t WHERE id > 100");
            assertEquals(0, r.rows().size());
        }
    }
}
