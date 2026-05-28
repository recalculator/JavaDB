package com.javadb.index;

import com.javadb.storage.RowId;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class BPlusTreeTest {

    @Test
    void insertAndSearch() {
        BPlusTree tree = new BPlusTree();
        tree.insert(10, new RowId(0, 0));
        tree.insert(20, new RowId(0, 1));
        tree.insert(5, new RowId(0, 2));

        Optional<RowId> result = tree.search(20);
        assertTrue(result.isPresent());
        assertEquals(new RowId(0, 1), result.get());
    }

    @Test
    void searchMissingKeyReturnsEmpty() {
        BPlusTree tree = new BPlusTree();
        tree.insert(1, new RowId(0, 0));
        assertTrue(tree.search(999).isEmpty());
    }

    @Test
    void deleteRemovesKey() {
        BPlusTree tree = new BPlusTree();
        tree.insert(42, new RowId(1, 2));
        tree.delete(42);
        assertTrue(tree.search(42).isEmpty());
    }

    @Test
    void rangeSearch() {
        BPlusTree tree = new BPlusTree();
        for (int i = 1; i <= 10; i++) tree.insert(i, new RowId(0, i - 1));
        List<RowId> range = tree.rangeSearch(3, 7);
        assertEquals(5, range.size());
    }

    @Test
    void handlesLargeDataset() {
        BPlusTree tree = new BPlusTree();
        int n = 10_000;
        for (int i = 0; i < n; i++) tree.insert(i, new RowId(i / 100, i % 100));
        for (int i = 0; i < n; i++) assertTrue(tree.search(i).isPresent(), "Missing key: " + i);
    }

    @Test
    void splitsCorrectlyWithSmallOrder() {
        BPlusTree tree = new BPlusTree(4);
        for (int i = 1; i <= 20; i++) tree.insert(i, new RowId(0, i));
        for (int i = 1; i <= 20; i++) assertTrue(tree.search(i).isPresent());
    }
}
