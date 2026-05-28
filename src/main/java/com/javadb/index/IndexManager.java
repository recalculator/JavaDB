package com.javadb.index;

import com.javadb.storage.RowId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Manages B+ tree indexes. Each index is keyed by "tableName.columnName". */
public class IndexManager {

    private final Map<String, BPlusTree> indexes = new HashMap<>();

    public void createIndex(String table, String column) {
        indexes.put(key(table, column), new BPlusTree());
    }

    public boolean hasIndex(String table, String column) {
        return indexes.containsKey(key(table, column));
    }

    public void insert(String table, String column, int key, RowId rid) {
        getIndex(table, column).insert(key, rid);
    }

    public Optional<RowId> search(String table, String column, int key) {
        return getIndex(table, column).search(key);
    }

    public List<RowId> rangeSearch(String table, String column, int low, int high) {
        return getIndex(table, column).rangeSearch(low, high);
    }

    public void delete(String table, String column, int key) {
        getIndex(table, column).delete(key);
    }

    public void update(String table, String column, int key, RowId newRid) {
        getIndex(table, column).updateRowId(key, newRid);
    }

    public void dropIndex(String table, String column) {
        indexes.remove(key(table, column));
    }

    private BPlusTree getIndex(String table, String column) {
        BPlusTree tree = indexes.get(key(table, column));
        if (tree == null) throw new IllegalStateException("No index on " + table + "." + column);
        return tree;
    }

    private String key(String table, String column) {
        return table.toLowerCase() + "." + column.toLowerCase();
    }
}
