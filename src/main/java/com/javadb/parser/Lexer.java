package com.javadb.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Lexer {

    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        Map.entry("select", TokenType.SELECT),
        Map.entry("from", TokenType.FROM),
        Map.entry("where", TokenType.WHERE),
        Map.entry("insert", TokenType.INSERT),
        Map.entry("into", TokenType.INTO),
        Map.entry("values", TokenType.VALUES),
        Map.entry("update", TokenType.UPDATE),
        Map.entry("set", TokenType.SET),
        Map.entry("delete", TokenType.DELETE),
        Map.entry("create", TokenType.CREATE),
        Map.entry("table", TokenType.TABLE),
        Map.entry("int", TokenType.INT),
        Map.entry("string", TokenType.STRING_TYPE),
        Map.entry("and", TokenType.AND),
        Map.entry("or", TokenType.OR),
        Map.entry("between", TokenType.BETWEEN),
        Map.entry("explain", TokenType.EXPLAIN)
    );

    private final String input;
    private int pos = 0;

    public Lexer(String input) {
        this.input = input.trim();
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < input.length()) {
            skipWhitespace();
            if (pos >= input.length()) break;
            tokens.add(nextToken());
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private Token nextToken() {
        char c = input.charAt(pos);

        if (Character.isLetter(c) || c == '_') return readIdentifierOrKeyword();
        if (Character.isDigit(c) || (c == '-' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))))
            return readNumber();
        if (c == '\'') return readString();

        return switch (c) {
            case '*' -> { pos++; yield new Token(TokenType.STAR, "*"); }
            case ',' -> { pos++; yield new Token(TokenType.COMMA, ","); }
            case '(' -> { pos++; yield new Token(TokenType.LPAREN, "("); }
            case ')' -> { pos++; yield new Token(TokenType.RPAREN, ")"); }
            case ';' -> { pos++; yield new Token(TokenType.SEMICOLON, ";"); }
            case '=' -> { pos++; yield new Token(TokenType.EQ, "="); }
            case '<' -> {
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') { pos++; yield new Token(TokenType.LTE, "<="); }
                if (pos < input.length() && input.charAt(pos) == '>') { pos++; yield new Token(TokenType.NEQ, "<>"); }
                yield new Token(TokenType.LT, "<");
            }
            case '>' -> {
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') { pos++; yield new Token(TokenType.GTE, ">="); }
                yield new Token(TokenType.GT, ">");
            }
            case '!' -> {
                pos++;
                if (pos < input.length() && input.charAt(pos) == '=') { pos++; yield new Token(TokenType.NEQ, "!="); }
                throw new ParseException("Unexpected character: !");
            }
            default -> throw new ParseException("Unexpected character: " + c + " at position " + pos);
        };
    }

    private Token readIdentifierOrKeyword() {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
        String word = input.substring(start, pos);
        TokenType type = KEYWORDS.getOrDefault(word.toLowerCase(), TokenType.IDENTIFIER);
        return new Token(type, word);
    }

    private Token readNumber() {
        int start = pos;
        if (input.charAt(pos) == '-') pos++;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        return new Token(TokenType.NUMBER, input.substring(start, pos));
    }

    private Token readString() {
        pos++; // skip opening quote
        int start = pos;
        while (pos < input.length() && input.charAt(pos) != '\'') pos++;
        if (pos >= input.length()) throw new ParseException("Unterminated string literal");
        String value = input.substring(start, pos);
        pos++; // skip closing quote
        return new Token(TokenType.STRING_LITERAL, value);
    }
}
