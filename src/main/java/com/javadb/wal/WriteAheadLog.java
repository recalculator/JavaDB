package com.javadb.wal;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only write-ahead log persisted to a flat text file.
 *
 * Entry format:  TYPE|tableName[|value0|value1|...]
 *
 * Ordering contract (enforced by callers in Executor):
 *   1. Write WAL entry for the operation.
 *   2. Perform the storage mutation (flush to disk).
 *   3. Write COMMIT to WAL.
 *
 * A crash between steps 1 and 3 leaves a WAL entry without a COMMIT.
 * RecoveryManager discards such incomplete entries on the next startup.
 * A crash after step 3 means the data is already in storage — no redo needed.
 */
public class WriteAheadLog implements Closeable {

    private final File logFile;
    private BufferedWriter writer;

    public WriteAheadLog(File logFile) throws IOException {
        this.logFile = logFile;
        this.writer = new BufferedWriter(new FileWriter(logFile, true /* append */));
    }

    // ── Write operations ───────────────────────────────────────────────────────

    public void append(WalEntry entry) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.type().name()).append("|").append(entry.tableName());
        if (entry.values() != null) {
            for (Object v : entry.values()) {
                sb.append("|").append(v == null ? "NULL" : v.toString());
            }
        }
        writer.write(sb.toString());
        writer.newLine();
        writer.flush(); // flush on every entry for durability
    }

    public void logInsert(String table, Object[] values) throws IOException {
        append(new WalEntry(WalEntryType.INSERT, table, values));
    }

    public void logUpdate(String table, Object[] before, Object[] after) throws IOException {
        // Store before and after values concatenated; the column count from the
        // schema tells recovery where before ends and after begins.
        Object[] combined = new Object[before.length + after.length];
        System.arraycopy(before, 0, combined, 0, before.length);
        System.arraycopy(after, 0, combined, before.length, after.length);
        append(new WalEntry(WalEntryType.UPDATE, table, combined));
    }

    public void logDelete(String table, Object[] values) throws IOException {
        append(new WalEntry(WalEntryType.DELETE, table, values));
    }

    /**
     * Writes the COMMIT marker.
     * Must only be called after the corresponding storage mutation has been
     * flushed to disk. See ordering contract above.
     */
    public void commit() throws IOException {
        append(new WalEntry(WalEntryType.COMMIT, "", null));
        // Extra flush: the COMMIT marker must be durable before we return
        // so that recovery sees a complete transaction on the next startup.
        writer.flush();
    }

    // ── Read operations ────────────────────────────────────────────────────────

    public List<String> readAll() throws IOException {
        List<String> lines = new ArrayList<>();
        if (!logFile.exists()) return lines;
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) lines.add(line);
            }
        }
        return lines;
    }

    // ── Checkpoint / truncation ────────────────────────────────────────────────

    /**
     * Truncates the WAL file after a successful recovery checkpoint.
     * All committed data is already in the storage files, so the WAL history
     * is no longer needed. Future operations will append to a clean file.
     */
    public void checkpoint() throws IOException {
        writer.close();
        // Overwrite (not append) to truncate the file.
        writer = new BufferedWriter(new FileWriter(logFile, false));
        writer.flush();
    }

    /**
     * Truncates the WAL. Used in tests to reset state.
     */
    public void truncate() throws IOException {
        checkpoint();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
