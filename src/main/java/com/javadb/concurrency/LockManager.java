package com.javadb.concurrency;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Table-level reader-writer locking.
 * Multiple readers or one exclusive writer per table.
 */
public class LockManager {

    private final Map<String, ReadWriteLock> locks = new HashMap<>();

    private synchronized ReadWriteLock getLock(String table) {
        return locks.computeIfAbsent(table.toLowerCase(), k -> new ReentrantReadWriteLock());
    }

    public void acquireReadLock(String table) {
        getLock(table).readLock().lock();
    }

    public void releaseReadLock(String table) {
        getLock(table).readLock().unlock();
    }

    public void acquireWriteLock(String table) {
        getLock(table).writeLock().lock();
    }

    public void releaseWriteLock(String table) {
        getLock(table).writeLock().unlock();
    }

    public <T> T withReadLock(String table, LockAction<T> action) throws Exception {
        acquireReadLock(table);
        try {
            return action.execute();
        } finally {
            releaseReadLock(table);
        }
    }

    public <T> T withWriteLock(String table, LockAction<T> action) throws Exception {
        acquireWriteLock(table);
        try {
            return action.execute();
        } finally {
            releaseWriteLock(table);
        }
    }

    @FunctionalInterface
    public interface LockAction<T> {
        T execute() throws Exception;
    }
}
