package com.javadb.parser.ast;

import com.javadb.catalog.Column;
import java.util.List;

public record CreateTableStatement(String tableName, List<Column> columns) implements Statement {}
