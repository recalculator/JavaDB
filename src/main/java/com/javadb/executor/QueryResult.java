package com.javadb.executor;

import com.javadb.storage.Row;

import java.util.List;

public record QueryResult(List<String> columns, List<Row> rows, int affectedRows, String message) {

    public static QueryResult select(List<String> columns, List<Row> rows) {
        return new QueryResult(columns, rows, rows.size(), null);
    }

    public static QueryResult dml(int affected) {
        return new QueryResult(List.of(), List.of(), affected, affected + " row(s) affected");
    }

    public static QueryResult ddl(String message) {
        return new QueryResult(List.of(), List.of(), 0, message);
    }

    public void print() {
        if (message != null) {
            System.out.println(message);
            return;
        }
        System.out.println(String.join(" | ", columns));
        System.out.println("-".repeat(Math.max(20, columns.size() * 15)));
        for (Row row : rows) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(row.get(i));
            }
            System.out.println(sb);
        }
        System.out.println("(" + rows.size() + " row(s))");
    }
}
