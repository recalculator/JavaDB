package com.javadb.catalog;

/**
 * Records a named B+ tree index on a table column.
 * Persisted in catalog.cat so indexes survive restarts.
 */
public record IndexMetadata(String indexName, String tableName, String columnName) {}
