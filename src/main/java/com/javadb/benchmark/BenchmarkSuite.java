package com.javadb.benchmark;

import com.javadb.Database;
import com.javadb.executor.QueryResult;

import java.io.*;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * JavaDB Benchmark Suite
 *
 * Usage:
 *   java -cp javadb.jar com.javadb.benchmark.BenchmarkSuite [rowCount]
 *
 * rowCount defaults to 100_000. Pass 1000000 for the 1M-row run.
 *
 * Benchmarks:
 *   1. Insert throughput
 *   2. Full-scan point lookup vs indexed point lookup
 *   3. Full-scan range query vs indexed range query
 *   4. Concurrent indexed reads at 1 / 4 / 8 / 16 threads
 *
 * Each timed section is preceded by a warmup pass (not measured).
 * Results are printed as an aligned terminal table and saved to benchmark-results.csv.
 */
public class BenchmarkSuite {

    // ── Entry point ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        int rowCount = 100_000;
        if (args.length > 0) {
            try {
                rowCount = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid row count '" + args[0] + "', using default 100000");
            }
        }

        System.out.println("JavaDB Benchmark Suite");
        System.out.println("=".repeat(72));
        System.out.printf("JVM      : %s%n", System.getProperty("java.version"));
        System.out.printf("Time     : %s%n",
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        System.out.printf("Row count: %,d%n", rowCount);
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        int sampleKey  = rowCount / 2;
        int rangeLo    = rowCount * 4 / 10;    // 40% mark
        int rangeHi    = rowCount * 6 / 10;    // 60% mark — ~20% of dataset
        int warmup     = Math.min(500, rowCount / 100);
        int concurrentQueriesPerThread = Math.min(500, rowCount / 10);

        // ── 1. Insert throughput ───────────────────────────────────────────────
        header("1. Insert Throughput");
        {
            File dir = tmp("bench-insert");
            try (Database db = new Database(dir)) {
                db.execute("CREATE TABLE bench (id INT, name STRING, age INT)");
                db.execute("CREATE INDEX idx_bench_id ON bench(id)");

                // Warmup — negative IDs so they never collide with timed data.
                for (int i = 1; i <= warmup; i++) {
                    db.execute("INSERT INTO bench VALUES (-" + i + ", 'warm', 0)");
                }

                // Timed
                long start = nanos();
                for (int i = 1; i <= rowCount; i++) {
                    db.execute("INSERT INTO bench VALUES (" + i + ", 'user" + i + "', " + (i % 100) + ")");
                }
                long elapsed = nanos() - start;

                double rps = rowCount * 1e9 / elapsed;
                results.add(new BenchmarkResult(
                    "Insert " + fmt(rowCount) + " rows", "-", fmt(rowCount), ms(elapsed) + " ms",
                    rps, "+" + warmup + " warmup rows (unmeasured)"));
                row("Insert " + fmt(rowCount) + " rows", ms(elapsed) + " ms",
                    String.format("%.0f rows/s", rps),
                    "(+" + warmup + " warmup, unmeasured)");
            }
            deleteDir(dir);
        }

        // ── 2. Point lookup: full scan vs index ───────────────────────────────
        header("2. Point Lookup  (dataset = " + fmt(rowCount) + " rows, key = " + fmt(sampleKey) + ")");
        File pointDir = tmp("bench-point");
        try (Database db = new Database(pointDir)) {
            setupTable(db, rowCount, true);

            // Warmup
            for (int i = 0; i < warmup; i++) {
                db.execute("SELECT * FROM bench WHERE id = " + (i % rowCount + 1));
            }

            // Indexed point lookup
            long t0 = nanos();
            QueryResult ri = db.execute("SELECT * FROM bench WHERE id = " + sampleKey);
            long idxNs = nanos() - t0;
            assertTrue(ri.rows().size() == 1, "Index lookup returned " + ri.rows().size() + " rows");

            // Full-scan equivalent: use age column (unindexed INT) with a value that won't match
            long t1 = nanos();
            QueryResult rf = db.execute("SELECT * FROM bench WHERE age = -999");
            long scanNs = nanos() - t1;

            double speedup = (double) scanNs / idxNs;
            results.add(new BenchmarkResult("Point lookup (index)", "id=" + sampleKey,
                "1", ns(idxNs) + " ns", 0, "-"));
            results.add(new BenchmarkResult("Point lookup (full scan, no match)", "age=-999",
                "0", ms(scanNs) + " ms", 0, String.format("index %.0fx faster", speedup)));

            row("Index point lookup (1 row)",  ns(idxNs) + " ns",    "1 row",  "-");
            row("Full scan (no match)",         ms(scanNs) + " ms",   "0 rows", String.format("index ~%.0fx faster", speedup));

            System.out.println("  Plan check:");
            printPlan(db, "EXPLAIN SELECT * FROM bench WHERE id = " + sampleKey, "    idx  ");
            printPlan(db, "EXPLAIN SELECT * FROM bench WHERE age = -999",         "    scan ");
        }
        deleteDir(pointDir);

        // ── 3. Range scan: full scan vs index ─────────────────────────────────
        header("3. Range Scan  [" + fmt(rangeLo) + ", " + fmt(rangeHi) + "]  (~20% of dataset)");
        File rangeDir = tmp("bench-range");
        try (Database db = new Database(rangeDir)) {
            setupTable(db, rowCount, true);

            // Warmup
            for (int i = 0; i < warmup; i++) {
                int lo = (i * 100) % rowCount + 1;
                db.execute("SELECT * FROM bench WHERE id BETWEEN " + lo + " AND " + (lo + 50));
            }

            // Indexed range scan
            long t0 = nanos();
            QueryResult ri = db.execute(
                "SELECT * FROM bench WHERE id BETWEEN " + rangeLo + " AND " + rangeHi);
            long idxNs = nanos() - t0;
            int expected = rangeHi - rangeLo + 1;
            assertTrue(ri.rows().size() == expected,
                "Range scan returned " + ri.rows().size() + " rows, expected " + expected);

            // Full-scan range: unindexed age column, range that returns ~same fraction
            // age = i%100, so age BETWEEN 40 AND 60 returns ~21% of rows
            long t1 = nanos();
            QueryResult rf = db.execute("SELECT * FROM bench WHERE age BETWEEN 40 AND 60");
            long scanNs = nanos() - t1;

            double speedup  = (double) scanNs / idxNs;
            double rowsPerMs = expected / (idxNs / 1e6);
            results.add(new BenchmarkResult("Range scan (index)",
                fmt(rangeLo) + "-" + fmt(rangeHi), fmt(expected), ms(idxNs) + " ms",
                rowsPerMs * 1000, "-"));
            results.add(new BenchmarkResult("Range scan (full scan, age range)",
                "age 40-60", fmt(rf.rows().size()), ms(scanNs) + " ms", 0,
                String.format("index ~%.1fx faster", speedup)));

            row("Index range [" + fmt(rangeLo) + ", " + fmt(rangeHi) + "]",
                ms(idxNs) + " ms", fmt(expected) + " rows",
                String.format("%.0f rows/ms", rowsPerMs));
            row("Full scan range (age 40–60)",
                ms(scanNs) + " ms", fmt(rf.rows().size()) + " rows",
                String.format("index ~%.1fx faster", speedup));

            System.out.println("  Plan check:");
            printPlan(db, "EXPLAIN SELECT * FROM bench WHERE id BETWEEN " + rangeLo + " AND " + rangeHi, "    idx  ");
            printPlan(db, "EXPLAIN SELECT * FROM bench WHERE age BETWEEN 40 AND 60",                      "    scan ");
        }
        deleteDir(rangeDir);

        // ── 4. Concurrent indexed reads ───────────────────────────────────────
        header("4. Concurrent Indexed Reads  (" + concurrentQueriesPerThread + " queries/thread)");
        File concDir = tmp("bench-concurrent");
        try (Database db = new Database(concDir)) {
            setupTable(db, rowCount, true);

            // Warmup
            for (int i = 0; i < warmup; i++) {
                db.execute("SELECT * FROM bench WHERE id = " + (i % rowCount + 1));
            }

            int[] threadCounts = {1, 4, 8, 16};
            double baseline = -1;
            for (int threads : threadCounts) {
                long elapsed = runConcurrent(db, threads, concurrentQueriesPerThread, rowCount);
                int totalQ = threads * concurrentQueriesPerThread;
                double qps = totalQ * 1e3 / elapsed;
                if (baseline < 0) baseline = qps;
                double scale = qps / baseline;
                results.add(new BenchmarkResult(threads + " thread(s)",
                    fmt(totalQ) + " queries", "-", elapsed + " ms",
                    qps, String.format("%.2fx scale", scale)));
                row(threads + " thread(s)", elapsed + " ms",
                    String.format("%.0f qps", qps),
                    String.format("%.2fx scale vs 1-thread", scale));
            }
        }
        deleteDir(concDir);

        // ── CSV + summary ──────────────────────────────────────────────────────
        writeCsv(results);

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("Results saved to benchmark-results.csv");
        System.out.println();
        System.out.println("Note: each INSERT does one synchronous WAL flush + one page write.");
        System.out.println("Throughput is bottlenecked by I/O, not JIT warmup.");
        System.out.println("For 1M rows, run: java -cp javadb.jar com.javadb.benchmark.BenchmarkSuite 1000000");
    }

    // ── Table setup ────────────────────────────────────────────────────────────

    private static void setupTable(Database db, int n, boolean withIndex) throws Exception {
        db.execute("CREATE TABLE bench (id INT, name STRING, age INT)");
        if (withIndex) {
            db.execute("CREATE INDEX idx_bench_id ON bench(id)");
        }
        for (int i = 1; i <= n; i++) {
            db.execute("INSERT INTO bench VALUES (" + i + ", 'user" + i + "', " + (i % 100) + ")");
        }
    }

    private static long runConcurrent(Database db, int threads, int queriesPerThread, int rowCount)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Future<?>[] futures = new Future[threads];
        long start = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            final int base = (t * queriesPerThread) % rowCount + 1;
            futures[t] = pool.submit(() -> {
                try {
                    for (int i = 0; i < queriesPerThread; i++) {
                        int key = (base + i - 1) % rowCount + 1;
                        db.execute("SELECT * FROM bench WHERE id = " + key);
                    }
                } catch (Exception e) {
                    System.err.println("[bench] thread error: " + e.getMessage());
                }
            });
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        return System.currentTimeMillis() - start;
    }

    // ── Formatting ─────────────────────────────────────────────────────────────

    private static void header(String title) {
        System.out.println();
        System.out.println("── " + title);
        System.out.printf("  %-44s %-14s %-18s %s%n", "Benchmark", "Time", "Result", "Notes");
        System.out.println("  " + "-".repeat(90));
    }

    private static void row(String name, String time, String result, String notes) {
        System.out.printf("  %-44s %-14s %-18s %s%n", name, time, result, notes);
    }

    private static void printPlan(Database db, String sql, String prefix) {
        try {
            QueryResult r = db.execute(sql);
            String firstLine = r.message().split("\n")[0];
            System.out.println(prefix + ": " + firstLine);
        } catch (Exception e) {
            System.out.println(prefix + ": ERROR " + e.getMessage());
        }
    }

    private static String fmt(int n) { return String.format("%,d", n); }
    private static String ms(long nanos) { return String.format("%.1f", nanos / 1e6); }
    private static String ns(long nanos) { return String.format("%,d", nanos); }
    private static long nanos() { return System.nanoTime(); }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // ── CSV ────────────────────────────────────────────────────────────────────

    private static void writeCsv(List<BenchmarkResult> results) throws IOException {
        File csv = new File("benchmark-results.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(csv))) {
            pw.println("benchmark,predicate,rows,time,throughput,notes");
            for (BenchmarkResult r : results) {
                pw.printf("\"%s\",\"%s\",\"%s\",\"%s\",\"%.0f\",\"%s\"%n",
                    r.name(), r.predicate(), r.rows(), r.time(), r.throughput(), r.notes());
            }
        }
    }

    private record BenchmarkResult(
        String name, String predicate, String rows,
        String time, double throughput, String notes) {}

    // ── Filesystem ─────────────────────────────────────────────────────────────

    private static File tmp(String prefix) throws IOException {
        return Files.createTempDirectory(prefix).toFile();
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
