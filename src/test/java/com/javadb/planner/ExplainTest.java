package com.javadb.planner;

import com.javadb.Database;
import com.javadb.executor.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that EXPLAIN returns the correct plan type and that the planner
 * chooses INDEX_SCAN / INDEX_RANGE_SCAN when an index is available and
 * FULL_SCAN otherwise.
 */
class ExplainTest {

    // ── EXPLAIN plan types ─────────────────────────────────────────────────────

    @Test
    void explainEqualityUsesIndexScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, name STRING)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id = 42");
            assertNotNull(r.message());
            assertTrue(r.message().contains("INDEX_SCAN"),
                "Expected INDEX_SCAN, got: " + r.message());
            assertTrue(r.message().contains("id"));
            assertTrue(r.message().contains("42"));
        }
    }

    @Test
    void explainNoIndexUsesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            // name is a STRING column — no index is created on it.
            db.execute("CREATE TABLE t (id INT, name STRING)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE name = 'alice'");
            assertNotNull(r.message());
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
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id > 10");
            assertNotNull(r.message());
            assertTrue(r.message().contains("INDEX_RANGE_SCAN"),
                "Expected INDEX_RANGE_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainLtUsesIndexRangeScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id < 100");
            assertTrue(r.message().contains("INDEX_RANGE_SCAN"));
        }
    }

    @Test
    void explainBetweenUsesIndexRangeScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE id BETWEEN 10 AND 20");
            assertTrue(r.message().contains("INDEX_RANGE_SCAN"),
                "Expected INDEX_RANGE_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainRangeOnNonIndexedColumnUsesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            // Only the first INT column (id) is auto-indexed; age is not indexed.
            db.execute("CREATE TABLE t (id INT, age INT, name STRING)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM t WHERE age > 18");
            assertTrue(r.message().contains("FULL_SCAN"),
                "Non-indexed column should fall back to FULL_SCAN, got: " + r.message());
        }
    }

    @Test
    void explainOutputContainsTableName(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE employees (id INT, dept STRING)");
            QueryResult r = db.execute("EXPLAIN SELECT * FROM employees WHERE id = 1");
            assertTrue(r.message().contains("employees"),
                "EXPLAIN output should contain table name");
        }
    }

    // ── Range scan correctness vs full scan ────────────────────────────────────

    @Test
    void rangeGtMatchesFullScan(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            for (int i = 1; i <= 20; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            // Index range scan via WHERE id > 15
            QueryResult byIndex = db.execute("SELECT * FROM t WHERE id > 15");
            // Verify result is correct — should be ids 16..20 = 5 rows
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
            for (int i = 1; i <= 10; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "')");
            }
            QueryResult idx  = db.execute("SELECT * FROM t WHERE id >= 7");
            assertEquals(4, idx.rows().size(), "id >= 7 should return 4 rows (7,8,9,10)");
        }
    }

    @Test
    void rangeEmptyResultWhenNoneMatch(@TempDir File dir) throws Exception {
        try (Database db = new Database(dir)) {
            db.execute("CREATE TABLE t (id INT, val STRING)");
            for (int i = 1; i <= 5; i++) {
                db.execute("INSERT INTO t VALUES (" + i + ", 'v')");
            }
            QueryResult r = db.execute("SELECT * FROM t WHERE id > 100");
            assertEquals(0, r.rows().size());
        }
    }
}
