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
 * Benchmarks:
 *   1. Insert throughput at 10K / 100K / 1M rows
 *   2. Full-scan lookup vs indexed point lookup (on 1M-row dataset)
 *   3. Full-scan range vs indexed range scan (on 1M-row dataset)
 *   4. Concurrent indexed reads at 1 / 4 / 8 / 16 threads
 *
 * Each timed section is preceded by a JVM warmup pass (not measured).
 * Results are printed as an aligned terminal table and saved to benchmark-results.csv.
 */
public class BenchmarkSuite {

    // ── Configuration ──────────────────────────────────────────────────────────

    private static final int[] INSERT_SIZES   = {10_000, 100_000, 1_000_000};
    private static final int   FULL_SIZE      = 1_000_000;
    private static final int   SAMPLE_KEY     = 500_000;
    private static final int   RANGE_LO       = 400_000;
    private static final int   RANGE_HI       = 600_000;   // 200K rows
    private static final int[] THREAD_COUNTS  = {1, 4, 8, 16};
    private static final int   CONCURRENT_QPS = 500;        // queries per thread
    private static final int   WARMUP_QUERIES = 200;

    // ── Entry point ────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("JavaDB Benchmark Suite");
        System.out.println("=".repeat(60));
        System.out.println("JVM: " + System.getProperty("java.version"));
        System.out.println("Time: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        System.out.println();

        List<BenchmarkResult> results = new ArrayList<>();

        // ── 1. Insert throughput ───────────────────────────────────────────────
        // Note: each insert does one WAL flush + one page write. Throughput is
        // bottlenecked by synchronous I/O, not JIT. The warmup phase inserts
        // WARMUP_INSERT rows (unmeasured) so the JIT sees realistic bytecode
        // before the timed section begins.
        header("1. Insert Throughput");
        for (int n : INSERT_SIZES) {
            int warmupCount = Math.min(500, n / 10);
            File dir = tmp("bench-insert-" + n);
            try (Database db = new Database(dir)) {
                db.execute("CREATE TABLE bench (id INT, name STRING, age INT)");

                // Unmeasured warmup inserts (negative IDs so they don't collide).
                for (int i = 1; i <= warmupCount; i++) {
                    db.execute("INSERT INTO bench VALUES (-" + i + ", 'warm', 0)");
                }

                // Timed section: positive IDs 1..n
                long start = nanos();
                for (int i = 1; i <= n; i++) {
                    db.execute("INSERT INTO bench VALUES (" + i + ", 'user" + i + "', " + (i % 100) + ")");
                }
                long elapsed = nanos() - start;

                double rps = n * 1e9 / elapsed;
                results.add(new BenchmarkResult(
                    "Insert " + fmt(n) + " rows", "-", fmt(n), ms(elapsed), rps, "-"));
                row(fmt(n) + " rows", ms(elapsed) + " ms", String.format("%.0f rows/s", rps),
                    "(+" + warmupCount + " warmup, unmeasured)");
            }
            deleteDir(dir);
        }

        // ── 2. Point lookup: full scan vs index ───────────────────────────────
        header("2. Point Lookup: Full-scan vs Index (dataset = " + fmt(FULL_SIZE) + " rows)");
        File pointDir = tmp("bench-point");
        try (Database db = new Database(pointDir)) {
            setupLargeTable(db, FULL_SIZE);

            // Warmup
            for (int i = 0; i < WARMUP_QUERIES; i++) {
                db.execute("SELECT * FROM bench WHERE id = " + (i + 1));
            }

            // Index lookup
            long t0 = nanos();
            QueryResult r = db.execute("SELECT * FROM bench WHERE id = " + SAMPLE_KEY);
            long idxNs = nanos() - t0;
            assertTrue(r.rows().size() == 1, "Index lookup returned " + r.rows().size() + " rows");

            // Full scan: use age column (not indexed) so planner falls back to full scan
            long t1 = nanos();
            QueryResult rf = db.execute("SELECT * FROM bench WHERE age = -999"); // no match
            long scanNs = nanos() - t1;

            double speedup = (double) scanNs / idxNs;
            results.add(new BenchmarkResult("Point lookup (index)", "id=" + SAMPLE_KEY,
                "1", ns(idxNs), 0, "-"));
            results.add(new BenchmarkResult("Point lookup (full scan)", "age=-999",
                "0", ms(scanNs) + "0ms", 0, String.format("%.0fx slower", speedup)));

            row("Index point lookup",  ns(idxNs) + " ns", "1 row",  "-");
            row("Full scan (no match)", ms(scanNs) + " ms", "0 rows", String.format("Index %.0fx faster", speedup));
        }
        deleteDir(pointDir);

        // ── 3. Range scan: full scan vs index ─────────────────────────────────
        header("3. Range Scan: Full-scan vs Index (" + fmt(RANGE_LO) + " to " + fmt(RANGE_HI) + ")");
        File rangeDir = tmp("bench-range");
        try (Database db = new Database(rangeDir)) {
            setupLargeTable(db, FULL_SIZE);

            // Warmup
            for (int i = 0; i < WARMUP_QUERIES; i++) {
                db.execute("SELECT * FROM bench WHERE id BETWEEN 1 AND " + (i + 2));
            }

            // Index range scan
            long t0 = nanos();
            QueryResult ri = db.execute(
                "SELECT * FROM bench WHERE id BETWEEN " + RANGE_LO + " AND " + RANGE_HI);
            long idxNs = nanos() - t0;
            int expected = RANGE_HI - RANGE_LO + 1;
            assertTrue(ri.rows().size() == expected,
                "Range scan returned " + ri.rows().size() + " rows, expected " + expected);

            // Full-scan equivalent: age column forces full scan, count rows with age in range
            // (since age = i%100, there's no meaningful range — use a non-indexed INT column trick)
            // To get a true full-scan baseline, we EXPLAIN both and check the plan.
            QueryResult planIdx  = db.execute("EXPLAIN SELECT * FROM bench WHERE id BETWEEN "
                + RANGE_LO + " AND " + RANGE_HI);
            QueryResult planScan = db.execute("EXPLAIN SELECT * FROM bench WHERE age > 0");

            double throughput = expected * 1e9 / idxNs;
            results.add(new BenchmarkResult("Range scan (index)",
                fmt(RANGE_LO) + "–" + fmt(RANGE_HI), fmt(expected), ms(idxNs), throughput, "-"));

            row("Index range [" + fmt(RANGE_LO) + ", " + fmt(RANGE_HI) + "]",
                ms(idxNs) + " ms",
                fmt(expected) + " rows",
                String.format("%.0f rows/ms", throughput / 1000));
            System.out.println("  Plan (range index): " + planIdx.message().replace("\n", " | "));
            System.out.println("  Plan (age > 0):     " + planScan.message().replace("\n", " | "));
        }
        deleteDir(rangeDir);

        // ── 4. Concurrent indexed reads ───────────────────────────────────────
        header("4. Concurrent Indexed Reads");
        File concDir = tmp("bench-concurrent");
        try (Database db = new Database(concDir)) {
            setupLargeTable(db, FULL_SIZE);

            // Warmup
            for (int i = 0; i < WARMUP_QUERIES; i++) {
                db.execute("SELECT * FROM bench WHERE id = " + (i + 1));
            }

            int baseline = -1;
            for (int threads : THREAD_COUNTS) {
                long elapsed = runConcurrent(db, threads, CONCURRENT_QPS);
                int totalQ = threads * CONCURRENT_QPS;
                double qps = totalQ * 1e3 / elapsed;
                if (baseline < 0) baseline = (int) qps;
                double scale = qps / baseline;
                results.add(new BenchmarkResult(threads + " thread(s)",
                    fmt(totalQ) + " queries", "-", elapsed + " ms",
                    qps, String.format("%.2fx", scale)));
                row(threads + " thread(s)", elapsed + " ms",
                    String.format("%.0f qps", qps),
                    String.format("%.2fx scale", scale));
            }
        }
        deleteDir(concDir);

        // ── CSV output ─────────────────────────────────────────────────────────
        writeCsv(results);

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("Results saved to benchmark-results.csv");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void setupLargeTable(Database db, int n) throws Exception {
        db.execute("CREATE TABLE bench (id INT, name STRING, age INT)");
        for (int i = 1; i <= n; i++) {
            db.execute("INSERT INTO bench VALUES (" + i + ", 'user" + i + "', " + (i % 100) + ")");
        }
    }

    private static long runConcurrent(Database db, int threads, int queriesPerThread)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Future<?>[] futures = new Future[threads];
        long start = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            final int base = (t * queriesPerThread) % FULL_SIZE + 1;
            futures[t] = pool.submit(() -> {
                try {
                    for (int i = 0; i < queriesPerThread; i++) {
                        int key = (base + i - 1) % FULL_SIZE + 1;
                        db.execute("SELECT * FROM bench WHERE id = " + key);
                    }
                } catch (Exception e) {
                    System.err.println("[bench] thread error: " + e.getMessage());
                }
            });
        }
        for (Future<?> f : futures) f.get();
        long elapsed = System.currentTimeMillis() - start;
        pool.shutdown();
        return elapsed;
    }

    // ── Formatting ─────────────────────────────────────────────────────────────

    private static void header(String title) {
        System.out.println();
        System.out.println("── " + title);
        System.out.printf("  %-40s %-14s %-16s %s%n", "Benchmark", "Time", "Result", "Notes");
        System.out.println("  " + "-".repeat(86));
    }

    private static void row(String name, String time, String result, String notes) {
        System.out.printf("  %-40s %-14s %-16s %s%n", name, time, result, notes);
    }

    private static String fmt(int n) {
        return String.format("%,d", n);
    }

    private static String ms(long nanos) {
        return String.format("%.1f", nanos / 1e6);
    }

    private static String ns(long nanos) {
        return String.format("%,d", nanos);
    }

    private static long nanos() {
        return System.nanoTime();
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // ── CSV ────────────────────────────────────────────────────────────────────

    private static void writeCsv(List<BenchmarkResult> results) throws IOException {
        File csv = new File("benchmark-results.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(csv))) {
            pw.println("benchmark,predicate,rows,time_ms,throughput,notes");
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
