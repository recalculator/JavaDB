package com.javadb.storage;

import com.javadb.catalog.DataType;
import com.javadb.catalog.TableSchema;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size 4 KB page storing serialized rows.
 * Format: [rowCount:int][row0][row1]...[rowN]
 * Each INT field: 4 bytes. Each STRING field: [len:int][utf bytes].
 */
public class Page {
    public static final int PAGE_SIZE = 4096;

    private final List<Row> rows;
    private boolean dirty;

    public Page() {
        this.rows = new ArrayList<>();
        this.dirty = true;
    }

    private Page(List<Row> rows) {
        this.rows = rows;
        this.dirty = false;
    }

    public List<Row> getRows() {
        return List.copyOf(rows);
    }

    public void addRow(Row row) {
        rows.add(row);
        dirty = true;
    }

    public void replaceRow(int index, Row row) {
        rows.set(index, row);
        dirty = true;
    }

    public void deleteRow(int index) {
        rows.remove(index);
        dirty = true;
    }

    public int rowCount() {
        return rows.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    public byte[] serialize(TableSchema schema) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(PAGE_SIZE);
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(rows.size());
        for (Row row : rows) {
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
            throw new IOException("Page overflow: " + data.length + " bytes");
        }
        byte[] padded = new byte[PAGE_SIZE];
        System.arraycopy(data, 0, padded, 0, data.length);
        dirty = false;
        return padded;
    }

    public static Page deserialize(byte[] data, TableSchema schema) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
        int count = dis.readInt();
        List<Row> rows = new ArrayList<>(count);
        for (int r = 0; r < count; r++) {
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
            rows.add(new Row(values));
        }
        return new Page(rows);
    }
}
