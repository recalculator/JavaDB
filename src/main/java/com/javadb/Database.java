package com.javadb;

import com.javadb.catalog.Catalog;
import com.javadb.concurrency.LockManager;
import com.javadb.executor.Executor;
import com.javadb.executor.QueryResult;
import com.javadb.index.IndexManager;
import com.javadb.parser.Parser;
import com.javadb.parser.ast.Statement;
import com.javadb.recovery.RecoveryManager;
import com.javadb.storage.StorageEngine;
import com.javadb.wal.WriteAheadLog;

import java.io.File;
import java.io.IOException;

public class Database implements AutoCloseable {

    private final Catalog catalog;
    private final StorageEngine storage;
    private final IndexManager indexManager;
    private final LockManager lockManager;
    private final WriteAheadLog wal;
    private final RecoveryManager recovery;
    private final Executor executor;

    public Database(File dataDir) throws IOException {
        this.catalog = new Catalog();
        this.storage = new StorageEngine(dataDir);
        this.indexManager = new IndexManager();
        this.lockManager = new LockManager();
        this.wal = new WriteAheadLog(new File(dataDir, "wal.log"));
        this.recovery = new RecoveryManager(wal);
        this.executor = new Executor(catalog, storage, indexManager, lockManager, wal);
        recovery.recover(executor);
    }

    public QueryResult execute(String sql) throws Exception {
        Statement stmt = Parser.parse(sql.trim().replaceAll(";$", "").trim());
        return executor.execute(stmt);
    }

    @Override
    public void close() throws IOException {
        storage.close();
        wal.close();
    }
}
