package com.javadb.parser;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    @Test
    void tokenizesSelectStar() {
        List<Token> tokens = new Lexer("SELECT * FROM users").tokenize();
        assertEquals(TokenType.SELECT, tokens.get(0).type());
        assertEquals(TokenType.STAR, tokens.get(1).type());
        assertEquals(TokenType.FROM, tokens.get(2).type());
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type());
        assertEquals("users", tokens.get(3).value());
        assertEquals(TokenType.EOF, tokens.get(4).type());
    }

    @Test
    void tokenizesStringLiteral() {
        List<Token> tokens = new Lexer("INSERT INTO t VALUES (1, 'Ayaan', 20)").tokenize();
        boolean foundString = tokens.stream().anyMatch(t -> t.type() == TokenType.STRING_LITERAL && t.value().equals("Ayaan"));
        assertTrue(foundString);
    }

    @Test
    void tokenizesComparisonOperators() {
        List<Token> tokens = new Lexer("WHERE age >= 18 AND id != 5").tokenize();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.GTE));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.AND));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.NEQ));
    }

    @Test
    void throwsOnUnexpectedCharacter() {
        assertThrows(ParseException.class, () -> new Lexer("SELECT @ FROM t").tokenize());
    }
}
