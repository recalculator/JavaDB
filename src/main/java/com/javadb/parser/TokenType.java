package com.javadb.parser;

public enum TokenType {
    // Keywords
    SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE,
    CREATE, TABLE, INT, STRING_TYPE,
    AND, OR, BETWEEN, EXPLAIN,
    // Literals
    IDENTIFIER, NUMBER, STRING_LITERAL,
    // Symbols
    STAR, COMMA, LPAREN, RPAREN, SEMICOLON,
    EQ, NEQ, LT, GT, LTE, GTE,
    EOF
}
