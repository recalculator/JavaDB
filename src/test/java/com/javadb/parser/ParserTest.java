package com.javadb.parser;

import com.javadb.parser.ast.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parsesSelectStar() {
        SelectStatement stmt = (SelectStatement) Parser.parse("SELECT * FROM users");
        assertEquals("users", stmt.tableName());
        assertTrue(stmt.columns().isEmpty());
        assertNull(stmt.where());
    }

    @Test
    void parsesSelectWithWhere() {
        SelectStatement stmt = (SelectStatement) Parser.parse("SELECT name FROM users WHERE age > 18");
        assertEquals(1, stmt.columns().size());
        assertEquals("name", stmt.columns().get(0));
        assertNotNull(stmt.where());
        Expression.BinaryOp op = (Expression.BinaryOp) stmt.where();
        assertEquals(">", op.operator());
    }

    @Test
    void parsesInsert() {
        InsertStatement stmt = (InsertStatement) Parser.parse("INSERT INTO users VALUES (1, 'Ayaan', 20)");
        assertEquals("users", stmt.tableName());
        assertEquals(3, stmt.values().size());
        assertEquals(1, stmt.values().get(0));
        assertEquals("Ayaan", stmt.values().get(1));
        assertEquals(20, stmt.values().get(2));
    }

    @Test
    void parsesUpdate() {
        UpdateStatement stmt = (UpdateStatement) Parser.parse("UPDATE users SET age = 21 WHERE id = 1");
        assertEquals("users", stmt.tableName());
        assertEquals(21, stmt.assignments().get("age"));
        assertNotNull(stmt.where());
    }

    @Test
    void parsesDelete() {
        DeleteStatement stmt = (DeleteStatement) Parser.parse("DELETE FROM users WHERE id = 1");
        assertEquals("users", stmt.tableName());
        assertNotNull(stmt.where());
    }

    @Test
    void parsesCreateTable() {
        CreateTableStatement stmt = (CreateTableStatement) Parser.parse(
            "CREATE TABLE users (id INT, name STRING, age INT)");
        assertEquals("users", stmt.tableName());
        assertEquals(3, stmt.columns().size());
        assertEquals("id", stmt.columns().get(0).name());
    }

    @Test
    void throwsOnInvalidSyntax() {
        assertThrows(ParseException.class, () -> Parser.parse("SELECT FROM"));
    }
}
