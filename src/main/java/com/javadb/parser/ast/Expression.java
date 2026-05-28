package com.javadb.parser.ast;

public sealed interface Expression
    permits Expression.Column, Expression.Literal, Expression.BinaryOp {

    record Column(String name) implements Expression {}

    record Literal(Object value) implements Expression {}

    record BinaryOp(Expression left, String operator, Expression right) implements Expression {}
}
