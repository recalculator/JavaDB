package com.javadb.parser.ast;

/** EXPLAIN <select-statement> — asks the planner to describe its plan without executing. */
public record ExplainStatement(SelectStatement inner) implements Statement {}
