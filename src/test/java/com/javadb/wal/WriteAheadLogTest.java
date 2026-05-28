package com.javadb.wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WriteAheadLogTest {

    @Test
    void appendAndRead(@TempDir File tmpDir) throws Exception {
        File logFile = new File(tmpDir, "wal.log");
        try (WriteAheadLog wal = new WriteAheadLog(logFile)) {
            wal.logInsert("users", new Object[]{1, "Ayaan", 20});
            wal.commit();
        }
        try (WriteAheadLog wal = new WriteAheadLog(logFile)) {
            List<String> lines = wal.readAll();
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).startsWith("INSERT"));
            assertTrue(lines.get(1).startsWith("COMMIT"));
        }
    }

    @Test
    void truncateClearsLog(@TempDir File tmpDir) throws Exception {
        File logFile = new File(tmpDir, "wal.log");
        try (WriteAheadLog wal = new WriteAheadLog(logFile)) {
            wal.logInsert("t", new Object[]{1});
            wal.commit();
            wal.truncate();
        }
        try (WriteAheadLog wal = new WriteAheadLog(logFile)) {
            assertTrue(wal.readAll().isEmpty());
        }
    }
}
