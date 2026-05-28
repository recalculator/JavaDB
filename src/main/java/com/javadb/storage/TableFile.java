package com.javadb.storage;

import com.javadb.catalog.TableSchema;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the on-disk file for one table.
 * Layout: sequential PAGE_SIZE blocks, one block per page.
 *
 * RowId stability guarantee:
 *   A RowId(pageIndex, slotIndex) is assigned once at insert time and never
 *   changes. Deletes write a tombstone into the slot rather than compacting
 *   the slot array, so the slotIndex of every other row remains valid.
 */
public class TableFile implements Closeable {

    // Conservative threshold: stop filling a page when it reaches this many
    // live slots. Avoids the exception-as-control-flow pattern of the previous
    // implementation. 60 slots is well under the 4 KB limit for typical rows.
    private static final int MAX_SLOTS_PER_PAGE = 60;

    private final RandomAccessFile raf;
    private final TableSchema schema;
    private final List<Page> pageCache;

    public TableFile(File file, TableSchema schema) throws IOException {
        this.schema = schema;
        this.raf = new RandomAccessFile(file, "rw");
        this.pageCache = new ArrayList<>();
        loadAllPages();
    }

    private void loadAllPages() throws IOException {
        long length = raf.length();
        int pageCount = (int) (length / Page.PAGE_SIZE);
        for (int i = 0; i < pageCount; i++) {
            byte[] data = new byte[Page.PAGE_SIZE];
            raf.seek((long) i * Page.PAGE_SIZE);
            raf.readFully(data);
            pageCache.add(Page.deserialize(data, schema));
        }
    }

    // ── Insert ─────────────────────────────────────────────────────────────────

    /**
     * Appends a row to the last page, or starts a new page when the last page
     * is full. Returns the stable RowId for the new row.
     */
    public RowId insertRow(Row row) throws IOException {
        Page target;
        int pageIndex;

        if (!pageCache.isEmpty()) {
            pageIndex = pageCache.size() - 1;
            target = pageCache.get(pageIndex);
            if (target.slotCount() >= MAX_SLOTS_PER_PAGE) {
                // Last page is full; start a fresh one.
                target = new Page();
                pageCache.add(target);
                pageIndex = pageCache.size() - 1;
            }
        } else {
            target = new Page();
            pageCache.add(target);
            pageIndex = 0;
        }

        target.addRow(row);
        int slotIndex = target.slotCount() - 1;
        flushPage(pageIndex);
        return new RowId(pageIndex, slotIndex);
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    public Row getRow(RowId rid) {
        return pageCache.get(rid.pageIndex()).getSlot(rid.slotIndex());
    }

    // ── Update ─────────────────────────────────────────────────────────────────

    public void updateRow(RowId rid, Row row) throws IOException {
        pageCache.get(rid.pageIndex()).replaceSlot(rid.slotIndex(), row);
        flushPage(rid.pageIndex());
    }

    // ── Delete (tombstone) ─────────────────────────────────────────────────────

    /**
     * Marks the slot as deleted without moving any other slot.
     * The RowId of every other row in this page remains valid.
     */
    public void tombstoneRow(RowId rid) throws IOException {
        pageCache.get(rid.pageIndex()).tombstone(rid.slotIndex());
        flushPage(rid.pageIndex());
    }

    // ── Scan ───────────────────────────────────────────────────────────────────

    /** Returns all live (non-deleted) rows across all pages. */
    public List<Row> scanAll() {
        List<Row> result = new ArrayList<>();
        for (Page page : pageCache) {
            result.addAll(page.getLiveRows());
        }
        return result;
    }

    /**
     * Returns RowIds for all live (non-deleted) rows.
     * Tombstoned slots are skipped — they have no usable RowId.
     */
    public List<RowId> scanAllRowIds() {
        List<RowId> result = new ArrayList<>();
        for (int p = 0; p < pageCache.size(); p++) {
            List<Row> slots = pageCache.get(p).getAllSlots();
            for (int s = 0; s < slots.size(); s++) {
                if (!slots.get(s).deleted()) {
                    result.add(new RowId(p, s));
                }
            }
        }
        return result;
    }

    /**
     * Returns RowIds for ALL slots (live + tombstoned).
     * Used by index rebuild, which inspects every row including deletions.
     */
    public List<RowId> scanAllSlotIds() {
        List<RowId> result = new ArrayList<>();
        for (int p = 0; p < pageCache.size(); p++) {
            int count = pageCache.get(p).slotCount();
            for (int s = 0; s < count; s++) {
                result.add(new RowId(p, s));
            }
        }
        return result;
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private void flushPage(int pageIndex) throws IOException {
        byte[] data = pageCache.get(pageIndex).serialize(schema);
        raf.seek((long) pageIndex * Page.PAGE_SIZE);
        raf.write(data);
    }

    public void flush() throws IOException {
        for (int i = 0; i < pageCache.size(); i++) {
            flushPage(i);
        }
    }

    @Override
    public void close() throws IOException {
        flush();
        raf.close();
    }
}
