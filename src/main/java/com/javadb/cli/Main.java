package com.javadb.cli;

import com.javadb.Database;
import com.javadb.executor.QueryResult;

import java.io.File;
import java.util.Scanner;

/**
 * Interactive SQL REPL.
 *
 * Usage:
 *   java -jar javadb.jar [data-directory]
 *
 * Defaults to ./data if no directory is given.
 * Type SQL terminated by ';', then press Enter.
 * Multi-line input is supported: keep typing until ';' appears.
 * Type 'exit' or 'quit' (without semicolon) to stop.
 *
 * Recovery messages are written to stderr so they don't clutter the session
 * transcript when stdout is piped.
 */
public class Main {

    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        File dataDir = new File(args.length > 0 ? args[0] : "data");

        System.out.println("JavaDB " + VERSION);
        System.out.println("Data directory: " + dataDir.getAbsolutePath());
        System.out.println("Type SQL ending with ';', or 'exit' to quit.\n");

        try (Database db = new Database(dataDir)) {
            Scanner scanner = new Scanner(System.in);
            StringBuilder buffer = new StringBuilder();

            while (true) {
                // Prompt: show "javadb> " for a fresh line, "     -> " when continuing.
                System.out.print(buffer.length() == 0 ? "javadb> " : "     -> ");
                System.out.flush();

                if (!scanner.hasNextLine()) break;
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
                        // Print user-facing errors to stdout so they appear in the right order
                        // relative to query output; include the message but not a full stack trace.
                        System.out.println("Error: " + e.getMessage());
                    }
                    System.out.println();
                    buffer.setLength(0);
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal: " + e.getMessage());
            System.exit(1);
        }

        System.out.println("Bye.");
    }
}
