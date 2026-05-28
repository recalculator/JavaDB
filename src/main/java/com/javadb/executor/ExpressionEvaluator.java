package com.javadb.executor;

import com.javadb.catalog.TableSchema;
import com.javadb.parser.ast.Expression;
import com.javadb.storage.Row;

public class ExpressionEvaluator {

    public static boolean evaluate(Expression expr, Row row, TableSchema schema) {
        Object result = eval(expr, row, schema);
        if (result instanceof Boolean b) return b;
        throw new IllegalStateException("Expression did not evaluate to boolean: " + expr);
    }

    private static Object eval(Expression expr, Row row, TableSchema schema) {
        return switch (expr) {
            case Expression.Literal lit -> lit.value();
            case Expression.Column col -> row.get(schema.columnIndex(col.name()));
            case Expression.BinaryOp op -> evalBinaryOp(op, row, schema);
        };
    }

    @SuppressWarnings("unchecked")
    private static Object evalBinaryOp(Expression.BinaryOp op, Row row, TableSchema schema) {
        String operator = op.operator();

        if (operator.equalsIgnoreCase("AND")) {
            return evaluate(op.left(), row, schema) && evaluate(op.right(), row, schema);
        }
        if (operator.equalsIgnoreCase("OR")) {
            return evaluate(op.left(), row, schema) || evaluate(op.right(), row, schema);
        }

        Object left = eval(op.left(), row, schema);
        Object right = eval(op.right(), row, schema);

        return switch (operator) {
            case "=" -> compareEquals(left, right);
            case "!=" , "<>" -> !compareEquals(left, right);
            case "<" -> compare(left, right) < 0;
            case ">" -> compare(left, right) > 0;
            case "<=" -> compare(left, right) <= 0;
            case ">=" -> compare(left, right) >= 0;
            default -> throw new IllegalArgumentException("Unknown operator: " + operator);
        };
    }

    private static boolean compareEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        if (a instanceof Comparable ca && b instanceof Comparable cb) {
            return ca.compareTo(cb);
        }
        throw new IllegalArgumentException("Cannot compare: " + a + " and " + b);
    }
}
