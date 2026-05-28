package com.javadb.parser.ast;

public record DeleteStatement(String tableName, Expression where) implements Statement {}
