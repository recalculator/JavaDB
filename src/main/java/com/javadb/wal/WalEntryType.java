package com.javadb.wal;

public enum WalEntryType {
    INSERT,
    UPDATE,
    DELETE,
    COMMIT,
    CHECKPOINT
}
