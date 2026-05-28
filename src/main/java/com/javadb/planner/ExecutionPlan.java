package com.javadb.planner;

import com.javadb.parser.ast.Expression;
import com.javadb.parser.ast.Statement;

public sealed interface ExecutionPlan
    permits ExecutionPlan.FullScan, ExecutionPlan.IndexScan, ExecutionPlan.DML, ExecutionPlan.DDL {

    record FullScan(Statement statement) implements ExecutionPlan {}

    record IndexScan(Statement statement, String indexColumn, int exactKey) implements ExecutionPlan {}

    record DML(Statement statement) implements ExecutionPlan {}

    record DDL(Statement statement) implements ExecutionPlan {}
}
