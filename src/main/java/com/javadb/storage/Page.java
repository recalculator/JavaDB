package com.javadb.storage;

import com.javadb.catalog.DataType;
import com.javadb.catalog.TableSchema;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size 4 KB page storing serialized rows.
 *
 * On-disk format per row:
 *   [deleted:byte(0|1)] [field0] [field1] ...
 *   INT field:    4 bytes
 *   STRING field: [len:int][utf bytes]
 *
 * Deletes write a tombstone (deleted=1) rather than removing the row from the
 * array. This keeps every RowId's slot index stable for the lifetime of the page,
 * which means B+ tree entries remain valid after a delete.
 *
 * Scans skip tombstoned slots. The page is never compacted (compaction is a
 * future optimisation — for correctness it is never needed).
 */
public class Page {
    public static final int PAGE_SIZE = 4096;

    // All slots, including tombstones. The slot index == RowId.slotIndex — never changes.
    private final List<Row> slots;
    private boolean dirty;

    public Page() {
        this.slots = new ArrayList<>();
        this.dirty = true;
    }

    private Page(List<Row> slots) {
        this.slots = slots;
        this.dirty = false;
    }

    /**
     * Returns all live (non-deleted) rows. The returned list is a copy and
     * does not include tombstones.
     */
    public List<Row> getLiveRows() {
        List<Row> live = new ArrayList<>();
        for (Row row : slots) {
            if (!row.deleted()) live.add(row);
        }
        return live;
    }

    /**
     * Returns the raw slot list (includes tombstones). Used by TableFile to
     * iterate slot indexes when building RowId lists.
     */
    public List<Row> getAllSlots() {
        return List.copyOf(slots);
    }

    public Row getSlot(int slotIndex) {
        return slots.get(slotIndex);
    }

    public void addRow(Row row) {
        slots.add(row);
        dirty = true;
    }

    public void replaceSlot(int slotIndex, Row row) {
        slots.set(slotIndex, row);
        dirty = true;
    }

    /**
     * Marks the slot as deleted (tombstone). The slot index is preserved so
     * existing RowIds remain valid.
     */
    public void tombstone(int slotIndex) {
        slots.set(slotIndex, slots.get(slotIndex).asDeleted());
        dirty = true;
    }

    public int slotCount() {
        return slots.size();
    }

    public int liveRowCount() {
        int count = 0;
        for (Row row : slots) if (!row.deleted()) count++;
        return count;
    }

    public boolean isDirty() {
        return dirty;
    }

    // ── Serialization ──────────────────────────────────────────────────────────

    public byte[] serialize(TableSchema schema) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(PAGE_SIZE);
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(slots.size());
        for (Row row : slots) {
            dos.writeByte(row.deleted() ? 1 : 0);
            for (int i = 0; i < schema.columns().size(); i++) {
                DataType type = schema.columns().get(i).type();
                if (type == DataType.INT) {
                    dos.writeInt((Integer) row.get(i));
                } else {
                    byte[] bytes = ((String) row.get(i)).getBytes("UTF-8");
                    dos.writeInt(bytes.length);
                    dos.write(bytes);
                }
            }
        }
        dos.flush();
        byte[] data = baos.toByteArray();
        if (data.length > PAGE_SIZE) {
            throw new IOException("Page overflow: " + data.length + " bytes (slots=" + slots.size() + ")");
        }
        byte[] padded = new byte[PAGE_SIZE];
        System.arraycopy(data, 0, padded, 0, data.length);
        dirty = false;
        return padded;
    }

    public static Page deserialize(byte[] data, TableSchema schema) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int count = dis.readInt();
        List<Row> slots = new ArrayList<>(count);
        for (int r = 0; r < count; r++) {
            boolean deleted = dis.readByte() == 1;
            Object[] values = new Object[schema.columns().size()];
            for (int i = 0; i < schema.columns().size(); i++) {
                DataType type = schema.columns().get(i).type();
                if (type == DataType.INT) {
                    values[i] = dis.readInt();
                } else {
                    int len = dis.readInt();
                    byte[] bytes = new byte[len];
                    dis.readFully(bytes);
                    values[i] = new String(bytes, "UTF-8");
                }
            }
            slots.add(new Row(values, deleted));
        }
        return new Page(slots);
    }
}
