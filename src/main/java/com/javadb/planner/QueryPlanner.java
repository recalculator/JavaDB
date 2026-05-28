package com.javadb.planner;

import com.javadb.index.IndexManager;
import com.javadb.parser.ast.*;

/**
 * Chooses an execution plan for each statement.
 *
 * Decision tree for SELECT:
 *   1. If WHERE is a single equality on an indexed INT column  → IndexScan
 *   2. If WHERE has a range bound on an indexed INT column     → IndexRangeScan
 *   3. Otherwise                                               → FullScan
 *
 * EXPLAIN wraps the inner plan in an Explain node without executing anything.
 */
public class QueryPlanner {

    // Sentinel values for open-ended range bounds.
    static final int RANGE_MIN = Integer.MIN_VALUE;
    static final int RANGE_MAX = Integer.MAX_VALUE;

    private final IndexManager indexManager;

    public QueryPlanner(IndexManager indexManager) {
        this.indexManager = indexManager;
    }

    public ExecutionPlan plan(Statement stmt) {
        return switch (stmt) {
            case ExplainStatement exp -> new ExecutionPlan.Explain(plan(exp.inner()));
            case SelectStatement sel  -> planSelect(sel);
            case InsertStatement ins  -> new ExecutionPlan.DML(ins);
            case UpdateStatement upd  -> new ExecutionPlan.DML(upd);
            case DeleteStatement del  -> new ExecutionPlan.DML(del);
            case CreateTableStatement crt -> new ExecutionPlan.DDL(crt);
        };
    }

    // ── SELECT planning ────────────────────────────────────────────────────────

    private ExecutionPlan planSelect(SelectStatement sel) {
        if (sel.where() == null) return new ExecutionPlan.FullScan(sel);

        // Try point-equality first (most selective).
        IndexHint point = extractPointHint(sel.tableName(), sel.where());
        if (point != null) {
            return new ExecutionPlan.IndexScan(sel, point.column(), point.key());
        }

        // Try range scan next.
        RangeHint range = extractRangeHint(sel.tableName(), sel.where());
        if (range != null) {
            return new ExecutionPlan.IndexRangeScan(sel, range.column(), range.low(), range.high());
        }

        return new ExecutionPlan.FullScan(sel);
    }

    // ── Point-equality hint ────────────────────────────────────────────────────

    /** Matches: col = val  or  val = col  (both orderings). */
    private IndexHint extractPointHint(String table, Expression expr) {
        if (!(expr instanceof Expression.BinaryOp op)) return null;
        if (!op.operator().equals("=")) return null;

        if (op.left() instanceof Expression.Column col && op.right() instanceof Expression.Literal lit
                && lit.value() instanceof Integer key
                && indexManager.hasIndex(table, col.name())) {
            return new IndexHint(col.name(), key);
        }
        if (op.right() instanceof Expression.Column col && op.left() instanceof Expression.Literal lit
                && lit.value() instanceof Integer key
                && indexManager.hasIndex(table, col.name())) {
            return new IndexHint(col.name(), key);
        }
        return null;
    }

    // ── Range hint ─────────────────────────────────────────────────────────────

    /**
     * Detects range predicates that can use the B+ tree rangeSearch.
     *
     * Handled forms:
     *   col > x      →  [x+1, MAX]
     *   col >= x     →  [x,   MAX]
     *   col < x      →  [MIN, x-1]
     *   col <= x     →  [MIN, x]
     *   col >= x AND col <= y  (BETWEEN desugars to this)  →  [x, y]
     *
     * We only match when the column has an index. If the AND combines two
     * different columns or non-range ops we fall back to FullScan.
     */
    private RangeHint extractRangeHint(String table, Expression expr) {
        // Single-sided: col OP literal
        if (expr instanceof Expression.BinaryOp op) {
            // BETWEEN desugars to (col >= lo) AND (col <= hi) in the parser.
            if (op.operator().equals("AND")) {
                return extractBetweenHint(table, op);
            }
            return extractOneSidedRange(table, op);
        }
        return null;
    }

    /** Handles (col >= lo) AND (col <= hi) — the BETWEEN desugar form. */
    private RangeHint extractBetweenHint(String table, Expression.BinaryOp and) {
        if (!(and.left() instanceof Expression.BinaryOp left)) return null;
        if (!(and.right() instanceof Expression.BinaryOp right)) return null;

        // Must be the same indexed column on both sides.
        String colLeft  = singleColumn(left);
        String colRight = singleColumn(right);
        if (colLeft == null || !colLeft.equals(colRight)) return null;
        if (!indexManager.hasIndex(table, colLeft)) return null;

        int lo = RANGE_MIN, hi = RANGE_MAX;

        Integer leftBound  = singleLiteral(left);
        Integer rightBound = singleLiteral(right);
        if (leftBound == null || rightBound == null) return null;

        lo = switch (left.operator()) {
            case ">="     -> leftBound;
            case ">"      -> leftBound + 1;
            case "<="     -> RANGE_MIN; // unusual but valid
            case "<"      -> RANGE_MIN;
            default       -> RANGE_MIN;
        };
        hi = switch (right.operator()) {
            case "<="     -> rightBound;
            case "<"      -> rightBound - 1;
            case ">="     -> RANGE_MAX;
            case ">"      -> RANGE_MAX;
            default       -> RANGE_MAX;
        };

        if (lo > hi) return null;
        return new RangeHint(colLeft, lo, hi);
    }

    /** Handles single-sided: col > x, col >= x, col < x, col <= x. */
    private RangeHint extractOneSidedRange(String table, Expression.BinaryOp op) {
        String col = null;
        Integer bound = null;
        boolean colOnLeft = false;

        if (op.left() instanceof Expression.Column c && op.right() instanceof Expression.Literal lit
                && lit.value() instanceof Integer b) {
            col = c.name();
            bound = b;
            colOnLeft = true;
        } else if (op.right() instanceof Expression.Column c && op.left() instanceof Expression.Literal lit
                && lit.value() instanceof Integer b) {
            col = c.name();
            bound = b;
            colOnLeft = false;
        }

        if (col == null || !indexManager.hasIndex(table, col)) return null;

        // Normalise: if col is on the right (e.g. "5 < col"), flip the operator.
        String operator = colOnLeft ? op.operator() : flip(op.operator());

        return switch (operator) {
            case ">"  -> new RangeHint(col, bound + 1, RANGE_MAX);
            case ">=" -> new RangeHint(col, bound,     RANGE_MAX);
            case "<"  -> new RangeHint(col, RANGE_MIN, bound - 1);
            case "<=" -> new RangeHint(col, RANGE_MIN, bound);
            default   -> null;
        };
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    /** Returns the single Column name if the expression is col OP literal, else null. */
    private String singleColumn(Expression.BinaryOp op) {
        if (op.left() instanceof Expression.Column c) return c.name();
        if (op.right() instanceof Expression.Column c) return c.name();
        return null;
    }

    /** Returns the literal Integer value if the expression is col OP literal, else null. */
    private Integer singleLiteral(Expression.BinaryOp op) {
        if (op.right() instanceof Expression.Literal lit && lit.value() instanceof Integer i) return i;
        if (op.left()  instanceof Expression.Literal lit && lit.value() instanceof Integer i) return i;
        return null;
    }

    /** Flips a comparison operator for when the column is on the right-hand side. */
    private String flip(String op) {
        return switch (op) {
            case ">"  -> "<";
            case ">=" -> "<=";
            case "<"  -> ">";
            case "<=" -> ">=";
            default   -> op;
        };
    }

    private record IndexHint(String column, int key) {}
    private record RangeHint(String column, int low, int high) {}
}
