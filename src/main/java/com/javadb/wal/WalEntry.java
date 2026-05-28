package com.javadb.wal;

import java.util.Arrays;

public record WalEntry(WalEntryType type, String tableName, Object[] values) {
    @Override
    public String toString() {
        return type + " " + tableName + " " + Arrays.toString(values);
    }
}
