package com.javadb.planner;

import com.javadb.parser.ast.Statement;

public sealed interface ExecutionPlan
    permits ExecutionPlan.FullScan, ExecutionPlan.IndexScan,
            ExecutionPlan.IndexRangeScan, ExecutionPlan.Explain,
            ExecutionPlan.DML, ExecutionPlan.DDL {

    /** Sequential scan of every (live) row in the table. */
    record FullScan(Statement statement) implements ExecutionPlan {}

    /** B+ tree point lookup: WHERE indexedCol = exactKey. */
    record IndexScan(Statement statement, String indexName, String indexColumn, int exactKey)
        implements ExecutionPlan {}

    /**
     * B+ tree range scan: WHERE indexedCol OP bound, or BETWEEN lo AND hi.
     * Covers >, >=, <, <=, and BETWEEN (which the parser desugars to >= AND <=).
     */
    record IndexRangeScan(Statement statement, String indexName, String indexColumn, int low, int high)
        implements ExecutionPlan {}

    /** EXPLAIN <select> — returns plan metadata without executing the query. */
    record Explain(ExecutionPlan inner) implements ExecutionPlan {}

    /** INSERT / UPDATE / DELETE. */
    record DML(Statement statement) implements ExecutionPlan {}

    /** CREATE TABLE or CREATE INDEX. */
    record DDL(Statement statement) implements ExecutionPlan {}
}
