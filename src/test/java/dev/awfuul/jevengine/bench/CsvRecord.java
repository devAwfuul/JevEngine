package dev.awfuul.jevengine.bench;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One row, addressable by header name rather than position. */
public final class CsvRecord {

    private final Map<String, String> byName;
    private final long rowNumber;

    public CsvRecord(String[] header, String[] fields, long rowNumber) {
        this.rowNumber = rowNumber;
        Map<String, String> map = new HashMap<>(header.length * 2);
        int limit = Math.min(header.length, fields.length);
        for (int i = 0; i < limit; i++) {
            map.put(header[i].trim().toLowerCase(Locale.ROOT), fields[i]);
        }
        this.byName = map;
    }

    public long rowNumber() {
        return rowNumber;
    }

    public String get(String column) {
        return byName.get(column.toLowerCase(Locale.ROOT));
    }

    public String getOrEmpty(String column) {
        String value = get(column);
        return value == null ? "" : value;
    }

    public boolean getBool(String column) {
        String value = get(column);
        return value != null && value.trim().equals("1");
    }

    public boolean has(String column) {
        return byName.containsKey(column.toLowerCase(Locale.ROOT));
    }
}
