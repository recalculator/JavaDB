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
        return dispatch(plan);
    }

    private QueryResult dispatch(ExecutionPlan plan) throws Exception {
        return switch (plan) {
            case ExecutionPlan.DDL ddl ->
                executeDDL((CreateTableStatement) ddl.statement());
            case ExecutionPlan.DML dml ->
                executeDML(dml.statement());
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

    private QueryResult executeDDL(CreateTableStatement stmt) throws IOException {
        TableSchema schema = new TableSchema(stmt.tableName(), stmt.columns());
        catalog.createTable(schema);
        storage.createTable(schema);
        // Auto-index the first INT column and record it in the catalog.
        for (Column col : stmt.columns()) {
            if (col.type() == com.javadb.catalog.DataType.INT) {
                indexManager.createIndex(stmt.tableName(), col.name());
                catalog.registerIndex(stmt.tableName(), col.name());
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

            // WAL ordering: write the intent before touching storage.
            // COMMIT is written only after storage succeeds so a crash between
            // the WAL entry and the COMMIT leaves an uncommitted entry that
            // recovery will discard.
            wal.logInsert(stmt.tableName(), row.values());

            RowId rid = tf.insertRow(row); // storage mutation

            wal.commit(); // COMMIT only after storage write succeeds

            // Update in-memory indexes.
            for (int i = 0; i < schema.columns().size(); i++) {
                Column col = schema.columns().get(i);
                if (col.type() == com.javadb.catalog.DataType.INT
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
            List<RowId> rids = tf.scanAllRowIds(); // only live rows
            int count = 0;
            for (RowId rid : rids) {
                Row row = tf.getRow(rid);
                if (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema)) {
                    Row updated = applyAssignments(row, stmt.assignments(), schema);

                    // WAL: write before -> after before touching storage.
                    wal.logUpdate(stmt.tableName(), row.values(), updated.values());

                    tf.updateRow(rid, updated); // storage mutation

                    wal.commit(); // COMMIT per-row update

                    // Keep indexes consistent for updated INT columns.
                    for (int i = 0; i < schema.columns().size(); i++) {
                        Column col = schema.columns().get(i);
                        if (col.type() == com.javadb.catalog.DataType.INT
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

            // Collect matching live rows first so we don't mutate during scan.
            List<RowId> toDelete = new ArrayList<>();
            for (RowId rid : tf.scanAllRowIds()) {
                Row row = tf.getRow(rid);
                if (stmt.where() == null || ExpressionEvaluator.evaluate(stmt.where(), row, schema)) {
                    toDelete.add(rid);
                }
            }

            // For each matched row: write WAL entry, tombstone storage, then COMMIT.
            // COMMIT is written after the tombstone succeeds so a crash before the
            // tombstone leaves a WAL entry without a matching COMMIT — recovery discards it.
            for (RowId rid : toDelete) {
                Row row = tf.getRow(rid);
                wal.logDelete(stmt.tableName(), row.values());

                tf.tombstoneRow(rid); // stable tombstone — no slot shifting

                wal.commit(); // COMMIT only after tombstone is on disk

                // Remove from index; the RowId itself is no longer reachable via scan.
                for (int j = 0; j < schema.columns().size(); j++) {
                    Column col = schema.columns().get(j);
                    if (col.type() == com.javadb.catalog.DataType.INT
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
            List<Row> all = tf.scanAll(); // already skips tombstones
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
                // Guard: the index may still hold a RID for a tombstoned row.
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
                // Guard tombstoned slots — index rebuild skips them, but a delete
                // between rebuild and this query could still leave a stale entry.
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
        String text = describePlan(inner);
        return QueryResult.explain(text);
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
                    + "\n  index col : " + is.indexColumn()
                    + "\n  lookup    : " + is.indexColumn() + " = " + is.exactKey();
            }
            case ExecutionPlan.IndexRangeScan irs -> {
                SelectStatement sel = (SelectStatement) irs.statement();
                String loStr = irs.low()  == Integer.MIN_VALUE ? "-∞" : String.valueOf(irs.low());
                String hiStr = irs.high() == Integer.MAX_VALUE ? "+∞" : String.valueOf(irs.high());
                yield "INDEX_RANGE_SCAN"
                    + "\n  table     : " + sel.tableName()
                    + "\n  index col : " + irs.indexColumn()
                    + "\n  range     : [" + loStr + ", " + hiStr + "]";
            }
            case ExecutionPlan.Explain exp -> describePlan(exp.inner());
            default -> plan.getClass().getSimpleName();
        };
    }

    // ── Index rebuild (called by Database on startup) ──────────────────────────

    /**
     * Scans every table file and populates the in-memory B+ tree indexes
     * according to the index metadata stored in the catalog.
     *
     * This is called once after the catalog is loaded. It is safe to call
     * even if the table file has tombstoned rows — those slots are skipped.
     */
    public void rebuildIndexes() throws IOException {
        for (Map.Entry<String, TableSchema> entry : catalog.getAllTables().entrySet()) {
            TableSchema schema = entry.getValue();
            List<String> idxCols = catalog.indexedColumnsFor(schema.tableName());
            if (idxCols.isEmpty()) continue;

            // Ensure in-memory index structures exist.
            for (String col : idxCols) {
                if (!indexManager.hasIndex(schema.tableName(), col)) {
                    indexManager.createIndex(schema.tableName(), col);
                }
            }

            TableFile tf = storage.openTable(schema);
            // Iterate every slot (live only — tombstones are skipped by scanAllRowIds).
            for (RowId rid : tf.scanAllRowIds()) {
                Row row = tf.getRow(rid);
                for (String colName : idxCols) {
                    int colIdx = schema.columnIndex(colName);
                    if (row.get(colIdx) instanceof Integer key) {
                        indexManager.insert(schema.tableName(), colName, key, rid);
                    }
                }
            }
        }
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
