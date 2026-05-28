package com.javadb.parser.ast;

import java.util.Map;

public record UpdateStatement(
    String tableName,
    Map<String, Object> assignments,
    Expression where
) implements Statement {}
