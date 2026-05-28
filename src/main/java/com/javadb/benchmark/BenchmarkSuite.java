package com.javadb.benchmark;

import com.javadb.Database;
import com.javadb.executor.QueryResult;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;

public class BenchmarkSuite {

    private static final int ROWS = 1_000_000;
    private static final int SAMPLE_KEY = 500_000;

    public static void main(String[] args) throws Exception {
        File tmpDir = Files.createTempDirectory("javadb-bench").toFile();
        System.out.println("Benchmark data dir: " + tmpDir);
        try (Database db = new Database(tmpDir)) {
            benchmarkInsert(db);
            benchmarkIndexLookup(db);
            benchmarkFullScan(db);
            benchmarkConcurrent(db);
        }
        deleteDir(tmpDir);
    }

    private static void benchmarkInsert(Database db) throws Exception {
        System.out.println("\n=== Benchmark 1: Insert Throughput ===");
        db.execute("CREATE TABLE bench (id INT, name STRING, age INT)");

        long start = System.currentTimeMillis();
        for (int i = 1; i <= ROWS; i++) {
            db.execute("INSERT INTO bench VALUES (" + i + ", 'user" + i + "', " + (i % 100) + ")");
            if (i % 100_000 == 0) System.out.print("  Inserted " + i + " rows\r");
        }
        long elapsed = System.currentTimeMillis() - start;
        double rps = ROWS * 1000.0 / elapsed;
        System.out.printf("\n  %,d rows in %,d ms (%.0f rows/sec)%n", ROWS, elapsed, rps);
    }

    private static void benchmarkIndexLookup(Database db) throws Exception {
        System.out.println("\n=== Benchmark 2: Index Lookup vs Full Scan ===");

        long t0 = System.nanoTime();
        QueryResult r = db.execute("SELECT * FROM bench WHERE id = " + SAMPLE_KEY);
        long indexTime = System.nanoTime() - t0;
        System.out.printf("  Index lookup: %,d ns (found %d row)%n", indexTime, r.rows().size());
    }

    private static void benchmarkFullScan(Database db) throws Exception {
        System.out.println("\n=== Benchmark 3: Full Table Scan (age filter) ===");
        long t0 = System.currentTimeMillis();
        QueryResult r = db.execute("SELECT * FROM bench WHERE age = 42");
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("  Full scan: %,d ms, found %,d rows%n", elapsed, r.rows().size());
    }

    private static void benchmarkConcurrent(Database db) throws Exception {
        System.out.println("\n=== Benchmark 4: Concurrent Reads ===");
        int[] threadCounts = {1, 4, 8, 16};
        for (int threads : threadCounts) {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            int queriesPerThread = 1000;
            long start = System.currentTimeMillis();
            Future<?>[] futures = new Future[threads];
            for (int t = 0; t < threads; t++) {
                int base = t * queriesPerThread;
                futures[t] = pool.submit(() -> {
                    try {
                        for (int i = 1; i <= queriesPerThread; i++) {
                            db.execute("SELECT * FROM bench WHERE id = " + (base % ROWS + 1));
                        }
                    } catch (Exception e) {
                        System.err.println("Thread error: " + e.getMessage());
                    }
                });
            }
            for (Future<?> f : futures) f.get();
            long elapsed = System.currentTimeMillis() - start;
            int totalQueries = threads * queriesPerThread;
            System.out.printf("  %2d threads, %,d queries: %,d ms (%.0f qps)%n",
                threads, totalQueries, elapsed, totalQueries * 1000.0 / elapsed);
            pool.shutdown();
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
