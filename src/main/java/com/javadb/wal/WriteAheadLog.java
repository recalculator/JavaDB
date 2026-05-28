package com.javadb.wal;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only write-ahead log persisted to disk.
 * Each entry is written as a line: TYPE|table|v0|v1|...
 * On startup the recovery manager replays all COMMIT-confirmed entries.
 */
public class WriteAheadLog implements Closeable {

    private final File logFile;
    private final BufferedWriter writer;

    public WriteAheadLog(File logFile) throws IOException {
        this.logFile = logFile;
        this.writer = new BufferedWriter(new FileWriter(logFile, true));
    }

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
        writer.flush();
    }

    public void logInsert(String table, Object[] values) throws IOException {
        append(new WalEntry(WalEntryType.INSERT, table, values));
    }

    public void logUpdate(String table, Object[] before, Object[] after) throws IOException {
        Object[] combined = new Object[before.length + after.length];
        System.arraycopy(before, 0, combined, 0, before.length);
        System.arraycopy(after, 0, combined, before.length, after.length);
        append(new WalEntry(WalEntryType.UPDATE, table, combined));
    }

    public void logDelete(String table, Object[] values) throws IOException {
        append(new WalEntry(WalEntryType.DELETE, table, values));
    }

    public void commit() throws IOException {
        append(new WalEntry(WalEntryType.COMMIT, "", null));
        writer.flush();
    }

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

    public void truncate() throws IOException {
        writer.close();
        new FileWriter(logFile, false).close();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
