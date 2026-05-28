package com.javadb.parser.ast;

public sealed interface Statement
    permits SelectStatement, InsertStatement, UpdateStatement, DeleteStatement, CreateTableStatement {}
