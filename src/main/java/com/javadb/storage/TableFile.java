package com.javadb.storage;

import com.javadb.catalog.TableSchema;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the on-disk file for one table.
 * Layout: sequential PAGE_SIZE blocks.
 */
public class TableFile implements Closeable {

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

    public List<Page> pages() {
        return pageCache;
    }

    public RowId insertRow(Row row) throws IOException {
        // Find a non-full page or create a new one
        // Heuristic: if last page exists and has < 100 rows, append there
        Page target;
        int pageIndex;
        if (!pageCache.isEmpty()) {
            pageIndex = pageCache.size() - 1;
            target = pageCache.get(pageIndex);
        } else {
            target = new Page();
            pageCache.add(target);
            pageIndex = 0;
        }

        // Check if page would overflow after adding this row
        target.addRow(row);
        try {
            target.serialize(schema); // validates size
        } catch (IOException overflow) {
            // Page overflowed — remove from this page, start a new one
            target.deleteRow(target.rowCount() - 1);
            flushPage(pageIndex);
            target = new Page();
            target.addRow(row);
            pageCache.add(target);
            pageIndex = pageCache.size() - 1;
        }

        int slotIndex = target.rowCount() - 1;
        flushPage(pageIndex);
        return new RowId(pageIndex, slotIndex);
    }

    public Row getRow(RowId rid) {
        return pageCache.get(rid.pageIndex()).getRows().get(rid.slotIndex());
    }

    public void updateRow(RowId rid, Row row) throws IOException {
        pageCache.get(rid.pageIndex()).replaceRow(rid.slotIndex(), row);
        flushPage(rid.pageIndex());
    }

    public void deleteRow(RowId rid) throws IOException {
        Page page = pageCache.get(rid.pageIndex());
        page.deleteRow(rid.slotIndex());
        flushPage(rid.pageIndex());
        // Shift slot indexes are managed by callers who must re-scan after delete
    }

    public List<Row> scanAll() {
        List<Row> result = new ArrayList<>();
        for (Page page : pageCache) {
            result.addAll(page.getRows());
        }
        return result;
    }

    public List<RowId> scanAllRowIds() {
        List<RowId> result = new ArrayList<>();
        for (int p = 0; p < pageCache.size(); p++) {
            List<Row> rows = pageCache.get(p).getRows();
            for (int s = 0; s < rows.size(); s++) {
                result.add(new RowId(p, s));
            }
        }
        return result;
    }

    private void flushPage(int pageIndex) throws IOException {
        Page page = pageCache.get(pageIndex);
        byte[] data = page.serialize(schema);
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
