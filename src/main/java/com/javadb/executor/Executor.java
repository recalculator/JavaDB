package com.javadb.executor;

import com.javadb.catalog.Catalog;
import com.javadb.catalog.Column;
import com.javadb.catalog.TableSchema;
import com.javadb.concurrency.LockManager;
import com.javadb.index.IndexManager;
import com.javadb.parser.ast.*;
import com.javadb.planner.ExecutionPlan;
import com.javadb.planner.QueryPlanner;
import com.javadb.storage.*;
import com.javadb.wal.WriteAheadLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Executor {

    private final Catalog catalog;
    private final StorageEngine storage;
    private final IndexManager indexManager;
    private final LockManager lockManager;
    private final WriteAheadLog wal;
    private final QueryPlanner planner;

    public Executor(Catalog catalog, StorageEngine storage, IndexManager indexManager,
                    LockManager lockManager, WriteAheadLog wal) {
        this.catalog = catalog;
        this.storage = storage;
        this.indexManager = indexManager;
        this.lockManager = lockManager;
        this.wal = wal;
        this.planner = new QueryPlanner(indexManager);
    }

    public QueryResult execute(Statement stmt) throws Exception {
        ExecutionPlan plan = planner.plan(stmt);
        return switch (plan) {
            case ExecutionPlan.DDL ddl -> executeDDL((CreateTableStatement) ddl.statement());
            case ExecutionPlan.DML dml -> executeDML(dml.statement());
            case ExecutionPlan.FullScan fs -> executeFullScan((SelectStatement) fs.statement());
            case ExecutionPlan.IndexScan is -> executeIndexScan((SelectStatement) is.statement(), is.indexColumn(), is.exactKey());
        };
    }

    // ── DDL ────────────────────────────────────────────────────────────────────

    private QueryResult executeDDL(CreateTableStatement stmt) throws IOException {
        TableSchema schema = new TableSchema(stmt.tableName(), stmt.columns());
        catalog.createTable(schema);
        storage.createTable(schema);
        // Auto-index first INT column
        for (Column col : stmt.columns()) {
            if (col.type() == com.javadb.catalog.DataType.INT) {
                indexManager.createIndex(stmt.tableName(), col.name());
                break;
            }
        }
        return QueryResult.ddl("Table " + stmt.tableName() + " created.");
    }

    // ── DML ────────────────────────────────────────────────────────────────────

    private QueryResult executeDML(Statement stmt) throws Exception {
        return switch (stmt) {
            case InsertStatement ins -> executeInsert(ins);
            case UpdateStatement upd -> executeUpdate(upd);
            case DeleteStatement del -> executeDelete(del);
            default -> throw new IllegalStateException("Unexpected DML: " + stmt);
        };
    }

    private QueryResult executeInsert(InsertStatement stmt) throws Exception {
        TableSchema schema = catalog.getTable(stmt.tableName());
        return lockManager.withWriteLock(stmt.tableName(), () -> {
            TableFile tf = storage.openTable(schema);
            Row row = new Row(stmt.values().toArray());
            wal.logInsert(stmt.tableName(), row.values());
            wal.commit();
            RowId rid = tf.insertRow(row);
            // Update indexes
            for (int i = 0; i < schema.columns().size(); i++) {
                Column col = schema.columns().get(i);
                if (col.type() == com.javadb.catalog.DataType.INT &&
                        indexManager.hasIndex(stmt.tableName(), col.name())) {
                    indexManager.insert(stmt.tableName(), col.name(), (Integer) row.get(i), rid);
                }
            }
            return QueryResult.dml(1);
        });
    }

    private QueryResult executeUpdate(UpdateStatement stmt) throws Exception {
        TableSchema schema = catalog.getTable(stmt.tableName());
        return lockManager.withWriteLock(stmt.tableName(), () -> {
            TableFile tf = storage.openTable(schema);
            List<RowId> rids = tf.scanAllRowIds();
            int count = 0;
            for (RowId rid : rids) {
                Row row = tf.getRow(rid);
                if (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema)) {
                    Row updated = applyAssignments(row, stmt.assignments(), schema);
                    wal.logUpdate(stmt.tableName(), row.values(), updated.values());
                    wal.commit();
                    tf.updateRow(rid, updated);
                    // Rebuild indexes for updated row
                    for (int i = 0; i < schema.columns().size(); i++) {
                        Column col = schema.columns().get(i);
                        if (col.type() == com.javadb.catalog.DataType.INT &&
                                indexManager.hasIndex(stmt.tableName(), col.name())) {
                            indexManager.delete(stmt.tableName(), col.name(), (Integer) row.get(i));
                            indexManager.insert(stmt.tableName(), col.name(), (Integer) updated.get(i), rid);
                        }
                    }
                    count++;
                }
            }
            return QueryResult.dml(count);
        });
    }

    private QueryResult executeDelete(DeleteStatement stmt) throws Exception {
        TableSchema schema = catalog.getTable(stmt.tableName());
        return lockManager.withWriteLock(stmt.tableName(), () -> {
            TableFile tf = storage.openTable(schema);
            // Collect rows to delete first (to avoid ConcurrentModification)
            List<RowId> toDelete = new ArrayList<>();
            for (RowId rid : tf.scanAllRowIds()) {
                Row row = tf.getRow(rid);
                if (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema)) {
                    toDelete.add(rid);
                    wal.logDelete(stmt.tableName(), row.values());
                }
            }
            wal.commit();
            // Delete in reverse order to preserve slot indexes
            for (int i = toDelete.size() - 1; i >= 0; i--) {
                RowId rid = toDelete.get(i);
                Row row = tf.getRow(rid);
                for (int j = 0; j < schema.columns().size(); j++) {
                    Column col = schema.columns().get(j);
                    if (col.type() == com.javadb.catalog.DataType.INT &&
                            indexManager.hasIndex(stmt.tableName(), col.name())) {
                        indexManager.delete(stmt.tableName(), col.name(), (Integer) row.get(j));
                    }
                }
                tf.deleteRow(rid);
            }
            return QueryResult.dml(toDelete.size());
        });
    }

    // ── SELECT ─────────────────────────────────────────────────────────────────

    private QueryResult executeFullScan(SelectStatement stmt) throws Exception {
        TableSchema schema = catalog.getTable(stmt.tableName());
        return lockManager.withReadLock(stmt.tableName(), () -> {
            TableFile tf = storage.openTable(schema);
            List<Row> all = tf.scanAll();
            List<Row> filtered = new ArrayList<>();
            for (Row row : all) {
                if (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema)) {
                    filtered.add(project(row, stmt.columns(), schema));
                }
            }
            List<String> resultColumns = resolveColumns(stmt.columns(), schema);
            return QueryResult.select(resultColumns, filtered);
        });
    }

    private QueryResult executeIndexScan(SelectStatement stmt, String indexCol, int key) throws Exception {
        TableSchema schema = catalog.getTable(stmt.tableName());
        return lockManager.withReadLock(stmt.tableName(), () -> {
            Optional<RowId> ridOpt = indexManager.search(stmt.tableName(), indexCol, key);
            List<Row> results = new ArrayList<>();
            if (ridOpt.isPresent()) {
                TableFile tf = storage.openTable(schema);
                Row row = tf.getRow(ridOpt.get());
                if (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema)) {
                    results.add(project(row, stmt.columns(), schema));
                }
            }
            List<String> resultColumns = resolveColumns(stmt.columns(), schema);
            return QueryResult.select(resultColumns, results);
        });
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private Row applyAssignments(Row row, Map<String, Object> assignments, TableSchema schema) {
        Row updated = row;
        for (Map.Entry<String, Object> entry : assignments.entrySet()) {
            int idx = schema.columnIndex(entry.getKey());
            updated = updated.with(idx, entry.getValue());
        }
        return updated;
    }

    private Row project(Row row, List<String> columns, TableSchema schema) {
        if (columns.isEmpty()) return row;
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            values[i] = row.get(schema.columnIndex(columns.get(i)));
        }
        return new Row(values);
    }

    private List<String> resolveColumns(List<String> requested, TableSchema schema) {
        if (requested.isEmpty()) {
            return schema.columns().stream().map(Column::name).toList();
        }
        return requested;
    }
}
