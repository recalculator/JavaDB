package com.javadb.storage;

import java.util.Arrays;
import java.util.List;

public record Row(Object[] values) {

    public Object get(int index) {
        return values[index];
    }

    public int size() {
        return values.length;
    }

    public Row with(int index, Object value) {
        Object[] copy = Arrays.copyOf(values, values.length);
        copy[index] = value;
        return new Row(copy);
    }

    public static Row of(Object... values) {
        return new Row(values);
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }
}
