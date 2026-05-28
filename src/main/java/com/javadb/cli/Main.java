package com.javadb.cli;

import com.javadb.Database;
import com.javadb.executor.QueryResult;

import java.io.File;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {
        File dataDir = new File(args.length > 0 ? args[0] : "data");
        System.out.println("JavaDB starting. Data directory: " + dataDir.getAbsolutePath());

        try (Database db = new Database(dataDir)) {
            System.out.println("JavaDB ready. Type SQL or 'exit' to quit.");
            Scanner scanner = new Scanner(System.in);
            StringBuilder buffer = new StringBuilder();

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) break;
                if (line.isEmpty()) continue;

                buffer.append(line).append(" ");
                String input = buffer.toString().trim();

                if (input.endsWith(";")) {
                    try {
                        QueryResult result = db.execute(input);
                        result.print();
                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                    buffer.setLength(0);
                }
            }
        }
        System.out.println("JavaDB stopped.");
    }
}
