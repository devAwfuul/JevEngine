package dev.awfuul.jevengine.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A streaming, RFC-4180-ish CSV reader.
 *
 * <p>The dataset this reads is described as gigantic, so the whole point is to
 * never hold more than one record in memory. It reads character by character
 * through a large buffer rather than line by line, because a quoted field can
 * legitimately contain a newline, and splitting on newlines first would corrupt
 * exactly the rows most likely to matter here: chat messages with line breaks.
 *
 * <p>Handles quoted fields, doubled quotes as an escaped quote, and both
 * {@code \n} and {@code \r\n} line endings. A byte-order mark on the first line
 * is stripped. Ragged rows (a different field count than the header) are
 * returned as-is; the caller decides how to handle that rather than this class
 * silently padding or truncating.
 */
public final class CsvReader implements AutoCloseable {

    private final Reader reader;
    private String[] header;
    private long rowNumber;

    private CsvReader(Reader reader) {
        this.reader = reader;
    }

    public static CsvReader open(Path path) throws IOException {
        Reader raw = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8);
        CsvReader csv = new CsvReader(new BufferedReader(raw, 1 << 20));
        try {
            // This dataset's export is preceded by a handful of lines
            // documenting the columns, each starting with '#'. They are not
            // data and not a header, so skip them before treating a line as
            // the header row.
            String[] header;
            while (true) {
                header = csv.readRecord();
                if (header == null) {
                    throw new IOException("The file has no header row after its comment lines.");
                }
                if (header.length == 0 || !header[0].startsWith("#")) {
                    break;
                }
            }
            if (header.length > 0 && header[0].startsWith("﻿")) {
                header[0] = header[0].substring(1);
            }
            csv.header = header;
            return csv;
        } catch (IOException | RuntimeException failure) {
            csv.close();
            throw failure;
        }
    }

    public String[] header() {
        return header;
    }

    /** The 1-based line most recently returned by {@link #readRecord()}, for error messages. */
    public long rowNumber() {
        return rowNumber;
    }

    /** Next data row, or null at end of file. */
    public String[] readRecord() throws IOException {
        List<String> fields = new ArrayList<>(16);
        StringBuilder field = new StringBuilder(64);
        boolean inQuotes = false;
        boolean sawAnyChar = false;

        int c;
        while ((c = reader.read()) != -1) {
            sawAnyChar = true;
            char ch = (char) c;

            if (inQuotes) {
                if (ch == '"') {
                    int next = peek();
                    if (next == '"') {
                        reader.read();
                        field.append('"');
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }

            switch (ch) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    fields.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    if (peek() == '\n') {
                        reader.read();
                    }
                    fields.add(field.toString());
                    rowNumber++;
                    return fields.toArray(new String[0]);
                }
                case '\n' -> {
                    fields.add(field.toString());
                    rowNumber++;
                    return fields.toArray(new String[0]);
                }
                default -> field.append(ch);
            }
        }

        if (!sawAnyChar) {
            return null;
        }
        // Final line with no trailing newline.
        fields.add(field.toString());
        rowNumber++;
        return fields.toArray(new String[0]);
    }

    private int peek() throws IOException {
        reader.mark(1);
        int next = reader.read();
        reader.reset();
        return next;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
