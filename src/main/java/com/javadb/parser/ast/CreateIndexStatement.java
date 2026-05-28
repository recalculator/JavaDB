package com.javadb.parser.ast;

public record CreateIndexStatement(
    String indexName,
    String tableName,
    String columnName
) implements Statement {}
