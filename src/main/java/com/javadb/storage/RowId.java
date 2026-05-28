package com.javadb.storage;

/** Physical location of a row: page index + slot within that page. */
public record RowId(int pageIndex, int slotIndex) {}
