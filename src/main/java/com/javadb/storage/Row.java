package com.javadb.storage;

import java.util.Arrays;

public record Row(Object[] values, boolean deleted) {

    /** Convenience constructor for a live (non-deleted) row. */
    public Row(Object[] values) {
        this(values, false);
    }

    public Object get(int index) {
        return values[index];
    }

    public int size() {
        return values.length;
    }

    /** Returns a new Row with one field replaced. Preserves the deleted flag. */
    public Row with(int index, Object value) {
        Object[] copy = Arrays.copyOf(values, values.length);
        copy[index] = value;
        return new Row(copy, this.deleted);
    }

    /** Returns a new Row marked as deleted. */
    public Row asDeleted() {
        return new Row(this.values, true);
    }

    public static Row of(Object... values) {
        return new Row(values, false);
    }

    @Override
    public String toString() {
        return (deleted ? "[DELETED]" : "") + Arrays.toString(values);
    }
}
