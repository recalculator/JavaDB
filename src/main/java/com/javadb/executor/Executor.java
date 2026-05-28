package com.javadb.executor;

import com.javadb.catalog.Catalog;
import com.javadb.catalog.Column;
import com.javadb.catalog.DataType;
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
        this.planner = new QueryPlanner(catalog);
    }

    public QueryResult execute(Statement stmt) throws Exception {
        ExecutionPlan plan = planner.plan(stmt);
        return dispatch(plan);
    }

    private QueryResult dispatch(ExecutionPlan plan) throws Exception {
        return switch (plan) {
            case ExecutionPlan.DDL ddl -> executeDDL(ddl.statement());
            case ExecutionPlan.DML dml -> executeDML(dml.statement());
            case ExecutionPlan.FullScan fs ->
                executeFullScan((SelectStatement) fs.statement());
            case ExecutionPlan.IndexScan is ->
                executeIndexScan((SelectStatement) is.statement(), is.indexColumn(), is.exactKey());
            case ExecutionPlan.IndexRangeScan irs ->
                executeIndexRangeScan((SelectStatement) irs.statement(), irs.indexColumn(), irs.low(), irs.high());
            case ExecutionPlan.Explain exp ->
                explainPlan(exp.inner());
        };
    }

    // ── DDL ────────────────────────────────────────────────────────────────────

    private QueryResult executeDDL(Statement stmt) throws Exception {
        return switch (stmt) {
            case CreateTableStatement crt -> executeCreateTable(crt);
            case CreateIndexStatement cri -> executeCreateIndex(cri);
            default -> throw new IllegalStateException("Unexpected DDL: " + stmt);
        };
    }

    private QueryResult executeCreateTable(CreateTableStatement stmt) throws IOException {
        TableSchema schema = new TableSchema(stmt.tableName(), stmt.columns());
        catalog.createTable(schema);
        storage.createTable(schema);
        return QueryResult.ddl("Table " + stmt.tableName() + " created.");
    }

    private QueryResult executeCreateIndex(CreateIndexStatement stmt) throws Exception {
        // Validate: table exists, column exists, column is INT, no duplicate index.
        // catalog.registerIndex throws with clear messages on any of these failures.
        catalog.registerIndex(stmt.indexName(), stmt.tableName(), stmt.columnName());

        // Create the in-memory B+ tree structure.
        indexManager.createIndex(stmt.tableName(), stmt.columnName());

        // Backfill: scan existing rows and populate the index.
        TableSchema schema = catalog.getTable(stmt.tableName());
        TableFile tf = storage.openTable(schema);
        int colIdx = schema.columnIndex(stmt.columnName());

        for (RowId rid : tf.scanAllRowIds()) {
            Row row = tf.getRow(rid);
            if (row.get(colIdx) instanceof Integer key) {
                indexManager.insert(stmt.tableName(), stmt.columnName(), key, rid);
            }
        }

        return QueryResult.ddl("Index " + stmt.indexName() + " created.");
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
        validateInsertValues(stmt, schema);
        return lockManager.withWriteLock(stmt.tableName(), () -> {
            TableFile tf = storage.openTable(schema);
            Row row = new Row(stmt.values().toArray());

            wal.logInsert(stmt.tableName(), row.values());
            RowId rid = tf.insertRow(row);
            wal.commit();

            for (int i = 0; i < schema.columns().size(); i++) {
                Column col = schema.columns().get(i);
                if (col.type() == DataType.INT
                        && indexManager.hasIndex(stmt.tableName(), col.name())) {
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
                    tf.updateRow(rid, updated);
                    wal.commit();

                    for (int i = 0; i < schema.columns().size(); i++) {
                        Column col = schema.columns().get(i);
                        if (col.type() == DataType.INT
                                && indexManager.hasIndex(stmt.tableName(), col.name())) {
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
            List<RowId> toDelete = new ArrayList<>();
            for (RowId rid : tf.scanAllRowIds()) {
                Row row = tf.getRow(rid);
                if (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema)) {
                    toDelete.add(rid);
                }
            }

            for (RowId rid : toDelete) {
                Row row = tf.getRow(rid);
                wal.logDelete(stmt.tableName(), row.values());
                tf.tombstoneRow(rid);
                wal.commit();

                for (int j = 0; j < schema.columns().size(); j++) {
                    Column col = schema.columns().get(j);
                    if (col.type() == DataType.INT
                            && indexManager.hasIndex(stmt.tableName(), col.name())) {
                        indexManager.delete(stmt.tableName(), col.name(), (Integer) row.get(j));
                    }
                }
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
            return QueryResult.select(resolveColumns(stmt.columns(), schema), filtered);
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
                if (!row.deleted()
                        && (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema))) {
                    results.add(project(row, stmt.columns(), schema));
                }
            }
            return QueryResult.select(resolveColumns(stmt.columns(), schema), results);
        });
    }

    private QueryResult executeIndexRangeScan(SelectStatement stmt, String indexCol,
                                               int low, int high) throws Exception {
        TableSchema schema = catalog.getTable(stmt.tableName());
        return lockManager.withReadLock(stmt.tableName(), () -> {
            List<RowId> rids = indexManager.rangeSearch(stmt.tableName(), indexCol, low, high);
            TableFile tf = storage.openTable(schema);
            List<Row> results = new ArrayList<>();
            for (RowId rid : rids) {
                Row row = tf.getRow(rid);
                if (!row.deleted()
                        && (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema))) {
                    results.add(project(row, stmt.columns(), schema));
                }
            }
            return QueryResult.select(resolveColumns(stmt.columns(), schema), results);
        });
    }

    // ── EXPLAIN ────────────────────────────────────────────────────────────────

    private QueryResult explainPlan(ExecutionPlan inner) {
        return QueryResult.explain(describePlan(inner));
    }

    private String describePlan(ExecutionPlan plan) {
        return switch (plan) {
            case ExecutionPlan.FullScan fs -> {
                SelectStatement sel = (SelectStatement) fs.statement();
                String predicate = sel.where() == null ? "(none)" : sel.where().toString();
                yield "FULL_SCAN"
                    + "\n  table     : " + sel.tableName()
                    + "\n  predicate : " + predicate;
            }
            case ExecutionPlan.IndexScan is -> {
                SelectStatement sel = (SelectStatement) is.statement();
                yield "INDEX_SCAN"
                    + "\n  table     : " + sel.tableName()
                    + "\n  index     : " + is.indexName()
                    + "\n  index col : " + is.indexColumn()
                    + "\n  lookup    : " + is.indexColumn() + " = " + is.exactKey();
            }
            case ExecutionPlan.IndexRangeScan irs -> {
                SelectStatement sel = (SelectStatement) irs.statement();
                String loStr = irs.low()  == Integer.MIN_VALUE ? "-∞" : String.valueOf(irs.low());
                String hiStr = irs.high() == Integer.MAX_VALUE ? "+∞" : String.valueOf(irs.high());
                yield "INDEX_RANGE_SCAN"
                    + "\n  table     : " + sel.tableName()
                    + "\n  index     : " + irs.indexName()
                    + "\n  index col : " + irs.indexColumn()
                    + "\n  range     : [" + loStr + ", " + hiStr + "]";
            }
            case ExecutionPlan.Explain exp -> describePlan(exp.inner());
            default -> plan.getClass().getSimpleName();
        };
    }

    // ── Index rebuild (called by Database on startup) ──────────────────────────

    public void rebuildIndexes() throws IOException {
        for (com.javadb.catalog.IndexMetadata idx : catalog.getAllIndexes()) {
            TableSchema schema = catalog.getTable(idx.tableName());
            if (!indexManager.hasIndex(idx.tableName(), idx.columnName())) {
                indexManager.createIndex(idx.tableName(), idx.columnName());
            }
            TableFile tf = storage.openTable(schema);
            int colIdx = schema.columnIndex(idx.columnName());
            for (RowId rid : tf.scanAllRowIds()) {
                Row row = tf.getRow(rid);
                if (row.get(colIdx) instanceof Integer key) {
                    indexManager.insert(idx.tableName(), idx.columnName(), key, rid);
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void validateInsertValues(InsertStatement stmt, TableSchema schema) {
        int expected = schema.columns().size();
        int actual   = stmt.values().size();
        if (actual != expected) {
            throw new IllegalArgumentException(
                "INSERT into " + stmt.tableName() + " expects " + expected
                + " value(s) but got " + actual);
        }
        for (int i = 0; i < expected; i++) {
            Column col = schema.columns().get(i);
            Object val = stmt.values().get(i);
            boolean typeOk = switch (col.type()) {
                case INT    -> val instanceof Integer;
                case STRING -> val instanceof String;
            };
            if (!typeOk) {
                throw new IllegalArgumentException(
                    "Column '" + col.name() + "' expects " + col.type()
                    + " but got " + (val == null ? "null" : val.getClass().getSimpleName())
                    + " (" + val + ")");
            }
        }
    }

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
