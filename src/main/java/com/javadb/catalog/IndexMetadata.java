package com.javadb.catalog;

/**
 * Records which column has a B+ tree index on a table.
 * Stored in the catalog file so indexes can be rebuilt on restart.
 */
public record IndexMetadata(String tableName, String columnName) {}
