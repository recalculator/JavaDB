package com.javadb.planner;

import com.javadb.index.IndexManager;
import com.javadb.parser.ast.*;

/**
 * Chooses between full table scan and index scan for SELECT queries.
 * For INSERT/UPDATE/DELETE/CREATE, routes to DML/DDL plan.
 */
public class QueryPlanner {

    private final IndexManager indexManager;

    public QueryPlanner(IndexManager indexManager) {
        this.indexManager = indexManager;
    }

    public ExecutionPlan plan(Statement stmt) {
        return switch (stmt) {
            case SelectStatement sel -> planSelect(sel);
            case InsertStatement ins -> new ExecutionPlan.DML(ins);
            case UpdateStatement upd -> new ExecutionPlan.DML(upd);
            case DeleteStatement del -> new ExecutionPlan.DML(del);
            case CreateTableStatement crt -> new ExecutionPlan.DDL(crt);
        };
    }

    private ExecutionPlan planSelect(SelectStatement sel) {
        if (sel.where() != null) {
            IndexHint hint = extractIndexHint(sel.tableName(), sel.where());
            if (hint != null) {
                return new ExecutionPlan.IndexScan(sel, hint.column(), hint.key());
            }
        }
        return new ExecutionPlan.FullScan(sel);
    }

    private IndexHint extractIndexHint(String table, Expression expr) {
        if (!(expr instanceof Expression.BinaryOp op)) return null;
        if (!op.operator().equals("=")) return null;
        if (op.left() instanceof Expression.Column col && op.right() instanceof Expression.Literal lit) {
            if (lit.value() instanceof Integer key && indexManager.hasIndex(table, col.name())) {
                return new IndexHint(col.name(), key);
            }
        }
        if (op.right() instanceof Expression.Column col && op.left() instanceof Expression.Literal lit) {
            if (lit.value() instanceof Integer key && indexManager.hasIndex(table, col.name())) {
                return new IndexHint(col.name(), key);
            }
        }
        return null;
    }

    private record IndexHint(String column, int key) {}
}
