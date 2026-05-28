package com.javadb.parser.ast;

import java.util.List;

public record SelectStatement(
    List<String> columns,   // empty = SELECT *
    String tableName,
    Expression where        // null = no filter
) implements Statement {}
