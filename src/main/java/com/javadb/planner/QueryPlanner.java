package com.javadb.planner;

import com.javadb.catalog.Catalog;
import com.javadb.catalog.IndexMetadata;
import com.javadb.parser.ast.*;

import java.util.Optional;

/**
 * Chooses an execution plan for each statement.
 *
 * Decision tree for SELECT:
 *   1. WHERE col = value on an indexed INT column   → IndexScan
 *   2. WHERE col op value (range) on indexed column → IndexRangeScan
 *   3. Otherwise                                    → FullScan
 *
 * Indexes are explicit — created via CREATE INDEX. There is no auto-indexing.
 */
public class QueryPlanner {

    static final int RANGE_MIN = Integer.MIN_VALUE;
    static final int RANGE_MAX = Integer.MAX_VALUE;

    private final Catalog catalog;

    public QueryPlanner(Catalog catalog) {
        this.catalog = catalog;
    }

    public ExecutionPlan plan(Statement stmt) {
        return switch (stmt) {
            case ExplainStatement exp       -> new ExecutionPlan.Explain(plan(exp.inner()));
            case SelectStatement sel        -> planSelect(sel);
            case InsertStatement ins        -> new ExecutionPlan.DML(ins);
            case UpdateStatement upd        -> new ExecutionPlan.DML(upd);
            case DeleteStatement del        -> new ExecutionPlan.DML(del);
            case CreateTableStatement crt   -> new ExecutionPlan.DDL(crt);
            case CreateIndexStatement cri   -> new ExecutionPlan.DDL(cri);
        };
    }

    // ── SELECT planning ────────────────────────────────────────────────────────

    private ExecutionPlan planSelect(SelectStatement sel) {
        if (sel.where() == null) return new ExecutionPlan.FullScan(sel);

        IndexHint point = extractPointHint(sel.tableName(), sel.where());
        if (point != null) {
            return new ExecutionPlan.IndexScan(sel, point.indexName(), point.column(), point.key());
        }

        RangeHint range = extractRangeHint(sel.tableName(), sel.where());
        if (range != null) {
            return new ExecutionPlan.IndexRangeScan(sel, range.indexName(), range.column(), range.low(), range.high());
        }

        return new ExecutionPlan.FullScan(sel);
    }

    // ── Point-equality hint ────────────────────────────────────────────────────

    private IndexHint extractPointHint(String table, Expression expr) {
        if (!(expr instanceof Expression.BinaryOp op)) return null;
        if (!op.operator().equals("=")) return null;

        if (op.left() instanceof Expression.Column col && op.right() instanceof Expression.Literal lit
                && lit.value() instanceof Integer key) {
            Optional<IndexMetadata> idx = catalog.findIndex(table, col.name());
            if (idx.isPresent()) return new IndexHint(idx.get().indexName(), col.name(), key);
        }
        if (op.right() instanceof Expression.Column col && op.left() instanceof Expression.Literal lit
                && lit.value() instanceof Integer key) {
            Optional<IndexMetadata> idx = catalog.findIndex(table, col.name());
            if (idx.isPresent()) return new IndexHint(idx.get().indexName(), col.name(), key);
        }
        return null;
    }

    // ── Range hint ─────────────────────────────────────────────────────────────

    private RangeHint extractRangeHint(String table, Expression expr) {
        if (!(expr instanceof Expression.BinaryOp op)) return null;
        if (op.operator().equals("AND")) return extractBetweenHint(table, op);
        return extractOneSidedRange(table, op);
    }

    private RangeHint extractBetweenHint(String table, Expression.BinaryOp and) {
        if (!(and.left() instanceof Expression.BinaryOp left)) return null;
        if (!(and.right() instanceof Expression.BinaryOp right)) return null;

        String colLeft  = singleColumn(left);
        String colRight = singleColumn(right);
        if (colLeft == null || !colLeft.equalsIgnoreCase(colRight)) return null;

        Optional<IndexMetadata> idx = catalog.findIndex(table, colLeft);
        if (idx.isEmpty()) return null;

        Integer leftBound  = singleLiteral(left);
        Integer rightBound = singleLiteral(right);
        if (leftBound == null || rightBound == null) return null;

        int lo = switch (left.operator()) {
            case ">="  -> leftBound;
            case ">"   -> leftBound + 1;
            default    -> RANGE_MIN;
        };
        int hi = switch (right.operator()) {
            case "<="  -> rightBound;
            case "<"   -> rightBound - 1;
            default    -> RANGE_MAX;
        };

        if (lo > hi) return null;
        return new RangeHint(idx.get().indexName(), colLeft, lo, hi);
    }

    private RangeHint extractOneSidedRange(String table, Expression.BinaryOp op) {
        String col = null;
        Integer bound = null;
        boolean colOnLeft = false;

        if (op.left() instanceof Expression.Column c && op.right() instanceof Expression.Literal lit
                && lit.value() instanceof Integer b) {
            col = c.name(); bound = b; colOnLeft = true;
        } else if (op.right() instanceof Expression.Column c && op.left() instanceof Expression.Literal lit
                && lit.value() instanceof Integer b) {
            col = c.name(); bound = b; colOnLeft = false;
        }

        if (col == null) return null;
        Optional<IndexMetadata> idx = catalog.findIndex(table, col);
        if (idx.isEmpty()) return null;

        String operator = colOnLeft ? op.operator() : flip(op.operator());
        String idxName  = idx.get().indexName();

        return switch (operator) {
            case ">"  -> new RangeHint(idxName, col, bound + 1, RANGE_MAX);
            case ">=" -> new RangeHint(idxName, col, bound,     RANGE_MAX);
            case "<"  -> new RangeHint(idxName, col, RANGE_MIN, bound - 1);
            case "<=" -> new RangeHint(idxName, col, RANGE_MIN, bound);
            default   -> null;
        };
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private String singleColumn(Expression.BinaryOp op) {
        if (op.left()  instanceof Expression.Column c) return c.name();
        if (op.right() instanceof Expression.Column c) return c.name();
        return null;
    }

    private Integer singleLiteral(Expression.BinaryOp op) {
        if (op.right() instanceof Expression.Literal lit && lit.value() instanceof Integer i) return i;
        if (op.left()  instanceof Expression.Literal lit && lit.value() instanceof Integer i) return i;
        return null;
    }

    private String flip(String op) {
        return switch (op) {
            case ">"  -> "<";
            case ">=" -> "<=";
            case "<"  -> ">";
            case "<=" -> ">=";
            default   -> op;
        };
    }

    private record IndexHint(String indexName, String column, int key) {}
    private record RangeHint(String indexName, String column, int low, int high) {}
}
