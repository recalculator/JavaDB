package com.javadb.index;

import com.javadb.storage.RowId;

import java.util.*;

/**
 * In-memory B+ tree mapping Integer keys to RowId values.
 * Order (branching factor) is configurable; default 128 for good fanout.
 */
@SuppressWarnings("unchecked")
public class BPlusTree {

    private static final int DEFAULT_ORDER = 128;

    private final int order;
    private Node root;

    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    public BPlusTree(int order) {
        this.order = order;
        this.root = new LeafNode();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void insert(int key, RowId rid) {
        InsertResult result = root.insert(key, rid);
        if (result != null) {
            InternalNode newRoot = new InternalNode();
            newRoot.keys.add(result.promotedKey());
            newRoot.children.add(result.left());
            newRoot.children.add(result.right());
            root = newRoot;
        }
    }

    public Optional<RowId> search(int key) {
        return root.search(key);
    }

    public List<RowId> rangeSearch(int low, int high) {
        List<RowId> result = new ArrayList<>();
        root.rangeSearch(low, high, result);
        return result;
    }

    public void delete(int key) {
        root.delete(key);
    }

    public void updateRowId(int key, RowId newRid) {
        delete(key);
        insert(key, newRid);
    }

    // ── Node types ─────────────────────────────────────────────────────────────

    private record InsertResult(int promotedKey, Node left, Node right) {}

    private abstract class Node {
        abstract Optional<RowId> search(int key);
        abstract InsertResult insert(int key, RowId rid);
        abstract void rangeSearch(int low, int high, List<RowId> result);
        abstract void delete(int key);
    }

    private class InternalNode extends Node {
        final List<Integer> keys = new ArrayList<>();
        final List<Node> children = new ArrayList<>();

        @Override
        public Optional<RowId> search(int key) {
            return children.get(childIndex(key)).search(key);
        }

        @Override
        public InsertResult insert(int key, RowId rid) {
            int idx = childIndex(key);
            InsertResult result = children.get(idx).insert(key, rid);
            if (result == null) return null;

            // Insert promoted key + right child
            int insertPos = upperBound(keys, result.promotedKey());
            keys.add(insertPos, result.promotedKey());
            children.set(idx, result.left());
            children.add(insertPos + 1, result.right());

            if (keys.size() < order) return null;
            return split();
        }

        private InsertResult split() {
            int mid = keys.size() / 2;
            int promoted = keys.get(mid);

            InternalNode right = new InternalNode();
            right.keys.addAll(keys.subList(mid + 1, keys.size()));
            right.children.addAll(children.subList(mid + 1, children.size()));

            keys.subList(mid, keys.size()).clear();
            children.subList(mid + 1, children.size()).clear();

            return new InsertResult(promoted, this, right);
        }

        @Override
        public void rangeSearch(int low, int high, List<RowId> result) {
            children.get(childIndex(low)).rangeSearch(low, high, result);
        }

        @Override
        public void delete(int key) {
            children.get(childIndex(key)).delete(key);
        }

        private int childIndex(int key) {
            int idx = upperBound(keys, key);
            return idx;
        }
    }

    private class LeafNode extends Node {
        final List<Integer> keys = new ArrayList<>();
        final List<RowId> rids = new ArrayList<>();
        LeafNode next; // linked list for range scans

        @Override
        public Optional<RowId> search(int key) {
            int idx = keys.indexOf(key);
            return idx >= 0 ? Optional.of(rids.get(idx)) : Optional.empty();
        }

        @Override
        public InsertResult insert(int key, RowId rid) {
            int pos = upperBound(keys, key);
            keys.add(pos, key);
            rids.add(pos, rid);
            if (keys.size() < order) return null;
            return split();
        }

        private InsertResult split() {
            int mid = keys.size() / 2;
            int promoted = keys.get(mid);

            LeafNode right = new LeafNode();
            right.keys.addAll(keys.subList(mid, keys.size()));
            right.rids.addAll(rids.subList(mid, rids.size()));
            right.next = this.next;
            this.next = right;

            keys.subList(mid, keys.size()).clear();
            rids.subList(mid, rids.size()).clear();

            return new InsertResult(promoted, this, right);
        }

        @Override
        public void rangeSearch(int low, int high, List<RowId> result) {
            LeafNode cur = this;
            while (cur != null) {
                for (int i = 0; i < cur.keys.size(); i++) {
                    int k = cur.keys.get(i);
                    if (k > high) return;
                    if (k >= low) result.add(cur.rids.get(i));
                }
                cur = cur.next;
            }
        }

        @Override
        public void delete(int key) {
            int idx = keys.indexOf(key);
            if (idx >= 0) {
                keys.remove(idx);
                rids.remove(idx);
            }
        }
    }

    private static int upperBound(List<Integer> list, int key) {
        int lo = 0, hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid) <= key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
