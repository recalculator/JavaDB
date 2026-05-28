package com.javadb.index;

import com.javadb.storage.RowId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that deletes do not corrupt B+ tree search for remaining keys.
 */
class BPlusTreeDeleteTest {

    @Test
    void searchCorrectAfterDeleteOfPrecedingKey() {
        BPlusTree tree = new BPlusTree(4); // small order to force splits
        tree.insert(10, new RowId(0, 0));
        tree.insert(20, new RowId(0, 1));
        tree.insert(30, new RowId(0, 2));

        tree.delete(10);

        assertTrue(tree.search(10).isEmpty(), "deleted key should not be found");
        assertEquals(new RowId(0, 1), tree.search(20).orElseThrow());
        assertEquals(new RowId(0, 2), tree.search(30).orElseThrow());
    }

    @Test
    void searchCorrectAfterDeleteOfSucceedingKey() {
        BPlusTree tree = new BPlusTree(4);
        tree.insert(10, new RowId(0, 0));
        tree.insert(20, new RowId(0, 1));
        tree.insert(30, new RowId(0, 2));

        tree.delete(30);

        assertEquals(new RowId(0, 0), tree.search(10).orElseThrow());
        assertEquals(new RowId(0, 1), tree.search(20).orElseThrow());
        assertTrue(tree.search(30).isEmpty());
    }

    @Test
    void reinsertAfterDeleteReturnsNewRowId() {
        BPlusTree tree = new BPlusTree();
        tree.insert(42, new RowId(0, 0));
        tree.delete(42);
        tree.insert(42, new RowId(1, 5)); // new physical location

        Optional<RowId> result = tree.search(42);
        assertTrue(result.isPresent());
        assertEquals(new RowId(1, 5), result.get());
    }

    @Test
    void rangeSearchExcludesDeletedKeys() {
        BPlusTree tree = new BPlusTree();
        for (int i = 1; i <= 10; i++) tree.insert(i, new RowId(0, i - 1));
        tree.delete(3);
        tree.delete(7);

        List<RowId> range = tree.rangeSearch(1, 10);
        assertEquals(8, range.size()); // 10 - 2 deleted

        // Verify deleted keys are not in the result.
        assertFalse(range.contains(new RowId(0, 2)), "key 3 (slot 2) should be absent");
        assertFalse(range.contains(new RowId(0, 6)), "key 7 (slot 6) should be absent");
    }

    @Test
    void massiveInsertDeleteSearchStaysCorrect() {
        BPlusTree tree = new BPlusTree(8); // small order to exercise many splits/merges
        int n = 500;
        for (int i = 0; i < n; i++) tree.insert(i, new RowId(i / 10, i % 10));

        // Delete every third key.
        for (int i = 0; i < n; i += 3) tree.delete(i);

        for (int i = 0; i < n; i++) {
            Optional<RowId> result = tree.search(i);
            if (i % 3 == 0) {
                assertTrue(result.isEmpty(), "Key " + i + " was deleted, should be absent");
            } else {
                assertTrue(result.isPresent(), "Key " + i + " should still be present");
                assertEquals(new RowId(i / 10, i % 10), result.get());
            }
        }
    }
}
