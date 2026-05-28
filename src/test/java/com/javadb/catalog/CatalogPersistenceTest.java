package com.javadb.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogPersistenceTest {

    @Test
    void tableSchemaRoundTrips(@TempDir File dir) throws Exception {
        Catalog c1 = new Catalog(dir);
        c1.createTable(new TableSchema("orders",
            List.of(new Column("id", DataType.INT), new Column("item", DataType.STRING))));

        // Second instance reads from the same directory — must see the table.
        Catalog c2 = new Catalog(dir);
        assertTrue(c2.tableExists("orders"));
        TableSchema schema = c2.getTable("orders");
        assertEquals("orders", schema.tableName());
        assertEquals(2, schema.columns().size());
        assertEquals("id",   schema.columns().get(0).name());
        assertEquals(DataType.INT, schema.columns().get(0).type());
        assertEquals("item", schema.columns().get(1).name());
        assertEquals(DataType.STRING, schema.columns().get(1).type());
    }

    @Test
    void indexMetadataRoundTrips(@TempDir File dir) throws Exception {
        Catalog c1 = new Catalog(dir);
        c1.createTable(new TableSchema("products",
            List.of(new Column("id", DataType.INT), new Column("name", DataType.STRING))));
        c1.registerIndex("products", "id");

        Catalog c2 = new Catalog(dir);
        List<String> idxCols = c2.indexedColumnsFor("products");
        assertEquals(1, idxCols.size());
        assertEquals("id", idxCols.get(0));

        List<IndexMetadata> allIdx = c2.getAllIndexes();
        assertEquals(1, allIdx.size());
        assertEquals("products", allIdx.get(0).tableName());
        assertEquals("id", allIdx.get(0).columnName());
    }

    @Test
    void multipleTablesAndIndexesRoundTrip(@TempDir File dir) throws Exception {
        Catalog c1 = new Catalog(dir);
        c1.createTable(new TableSchema("a",
            List.of(new Column("id", DataType.INT))));
        c1.createTable(new TableSchema("b",
            List.of(new Column("x", DataType.INT), new Column("y", DataType.STRING))));
        c1.registerIndex("a", "id");

        Catalog c2 = new Catalog(dir);
        assertTrue(c2.tableExists("a"));
        assertTrue(c2.tableExists("b"));
        assertEquals(1, c2.indexedColumnsFor("a").size());
        assertTrue(c2.indexedColumnsFor("b").isEmpty());
    }

    @Test
    void dropTableRemovesFromPersistence(@TempDir File dir) throws Exception {
        Catalog c1 = new Catalog(dir);
        c1.createTable(new TableSchema("tmp",
            List.of(new Column("id", DataType.INT))));
        c1.dropTable("tmp");

        Catalog c2 = new Catalog(dir);
        assertFalse(c2.tableExists("tmp"));
    }

    @Test
    void catalogFileIsIdempotentOnMultipleCreates(@TempDir File dir) throws Exception {
        // Creating the same table in separate sessions must produce a valid catalog.
        Catalog c1 = new Catalog(dir);
        c1.createTable(new TableSchema("t",
            List.of(new Column("id", DataType.INT))));

        // A second Catalog loading the file and trying to create the same table
        // must throw — not silently corrupt.
        Catalog c2 = new Catalog(dir);
        assertThrows(IllegalStateException.class, () ->
            c2.createTable(new TableSchema("t",
                List.of(new Column("id", DataType.INT)))));
    }
}
