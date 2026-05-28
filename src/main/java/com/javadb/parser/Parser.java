package com.javadb.parser;

import com.javadb.catalog.Column;
import com.javadb.catalog.DataType;
import com.javadb.parser.ast.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Parser {

    private final List<Token> tokens;
    private int pos = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Statement parse(String sql) {
        Lexer lexer = new Lexer(sql);
        List<Token> tokens = lexer.tokenize();
        return new Parser(tokens).parseStatement();
    }

    private Statement parseStatement() {
        Token t = peek();
        return switch (t.type()) {
            case EXPLAIN -> parseExplain();
            case SELECT -> parseSelect();
            case INSERT -> parseInsert();
            case UPDATE -> parseUpdate();
            case DELETE -> parseDelete();
            case CREATE -> parseCreate();
            default -> throw new ParseException("Unexpected token: " + t);
        };
    }

    private ExplainStatement parseExplain() {
        consume(TokenType.EXPLAIN);
        // EXPLAIN must be followed by a SELECT statement.
        if (peek().type() != TokenType.SELECT) {
            throw new ParseException("EXPLAIN only supports SELECT statements");
        }
        return new ExplainStatement(parseSelect());
    }

    private SelectStatement parseSelect() {
        consume(TokenType.SELECT);
        List<String> columns = new ArrayList<>();
        if (peek().type() == TokenType.STAR) {
            consume(TokenType.STAR);
        } else {
            columns.add(consumeIdentifier());
            while (peek().type() == TokenType.COMMA) {
                consume(TokenType.COMMA);
                columns.add(consumeIdentifier());
            }
        }
        consume(TokenType.FROM);
        String table = consumeIdentifier();
        Expression where = null;
        if (peek().type() == TokenType.WHERE) {
            consume(TokenType.WHERE);
            where = parseExpression();
        }
        return new SelectStatement(columns, table, where);
    }

    private InsertStatement parseInsert() {
        consume(TokenType.INSERT);
        consume(TokenType.INTO);
        String table = consumeIdentifier();
        consume(TokenType.VALUES);
        consume(TokenType.LPAREN);
        List<Object> values = new ArrayList<>();
        values.add(parseValue());
        while (peek().type() == TokenType.COMMA) {
            consume(TokenType.COMMA);
            values.add(parseValue());
        }
        consume(TokenType.RPAREN);
        return new InsertStatement(table, values);
    }

    private UpdateStatement parseUpdate() {
        consume(TokenType.UPDATE);
        String table = consumeIdentifier();
        consume(TokenType.SET);
        Map<String, Object> assignments = new LinkedHashMap<>();
        String col = consumeIdentifier();
        consume(TokenType.EQ);
        assignments.put(col, parseValue());
        while (peek().type() == TokenType.COMMA) {
            consume(TokenType.COMMA);
            col = consumeIdentifier();
            consume(TokenType.EQ);
            assignments.put(col, parseValue());
        }
        Expression where = null;
        if (peek().type() == TokenType.WHERE) {
            consume(TokenType.WHERE);
            where = parseExpression();
        }
        return new UpdateStatement(table, assignments, where);
    }

    private DeleteStatement parseDelete() {
        consume(TokenType.DELETE);
        consume(TokenType.FROM);
        String table = consumeIdentifier();
        Expression where = null;
        if (peek().type() == TokenType.WHERE) {
            consume(TokenType.WHERE);
            where = parseExpression();
        }
        return new DeleteStatement(table, where);
    }

    private CreateTableStatement parseCreate() {
        consume(TokenType.CREATE);
        consume(TokenType.TABLE);
        String table = consumeIdentifier();
        consume(TokenType.LPAREN);
        List<Column> columns = new ArrayList<>();
        columns.add(parseColumnDef());
        while (peek().type() == TokenType.COMMA) {
            consume(TokenType.COMMA);
            columns.add(parseColumnDef());
        }
        consume(TokenType.RPAREN);
        return new CreateTableStatement(table, columns);
    }

    private Column parseColumnDef() {
        String name = consumeIdentifier();
        DataType type = switch (peek().type()) {
            case INT -> { consume(TokenType.INT); yield DataType.INT; }
            case STRING_TYPE -> { consume(TokenType.STRING_TYPE); yield DataType.STRING; }
            default -> throw new ParseException("Expected data type, got: " + peek());
        };
        return new Column(name, type);
    }

    private Expression parseExpression() {
        Expression left = parseComparison();
        while (peek().type() == TokenType.AND || peek().type() == TokenType.OR) {
            String op = peek().value();
            pos++;
            Expression right = parseComparison();
            left = new Expression.BinaryOp(left, op.toUpperCase(), right);
        }
        return left;
    }

    private Expression parseComparison() {
        Expression left = parsePrimary();
        TokenType t = peek().type();
        if (t == TokenType.EQ || t == TokenType.NEQ || t == TokenType.LT ||
            t == TokenType.GT || t == TokenType.LTE || t == TokenType.GTE) {
            String op = peek().value();
            pos++;
            Expression right = parsePrimary();
            return new Expression.BinaryOp(left, op, right);
        }
        // BETWEEN x AND y  →  (left >= x) AND (left <= y)
        if (t == TokenType.BETWEEN) {
            consume(TokenType.BETWEEN);
            Expression lo = parsePrimary();
            consume(TokenType.AND);
            Expression hi = parsePrimary();
            Expression geLo = new Expression.BinaryOp(left, ">=", lo);
            Expression leHi = new Expression.BinaryOp(left, "<=", hi);
            return new Expression.BinaryOp(geLo, "AND", leHi);
        }
        return left;
    }

    private Expression parsePrimary() {
        Token t = peek();
        return switch (t.type()) {
            case IDENTIFIER -> { pos++; yield new Expression.Column(t.value()); }
            case NUMBER -> { pos++; yield new Expression.Literal(Integer.parseInt(t.value())); }
            case STRING_LITERAL -> { pos++; yield new Expression.Literal(t.value()); }
            default -> throw new ParseException("Unexpected token in expression: " + t);
        };
    }

    private Object parseValue() {
        Token t = peek();
        return switch (t.type()) {
            case NUMBER -> { pos++; yield Integer.parseInt(t.value()); }
            case STRING_LITERAL -> { pos++; yield t.value(); }
            default -> throw new ParseException("Expected value, got: " + t);
        };
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token consume(TokenType expected) {
        Token t = tokens.get(pos);
        if (t.type() != expected) {
            throw new ParseException("Expected " + expected + " but got " + t);
        }
        pos++;
        return t;
    }

    private String consumeIdentifier() {
        Token t = tokens.get(pos);
        if (t.type() != TokenType.IDENTIFIER) {
            throw new ParseException("Expected identifier but got " + t);
        }
        pos++;
        return t.value();
    }
}
