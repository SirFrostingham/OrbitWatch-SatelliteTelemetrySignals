package com.orbitwatch.ingest;

import com.orbitwatch.contracts.CatalogObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the CelesTrak SATCAT CSV. Header-driven so column reordering upstream does not break us;
 * missing columns yield nulls rather than failures.
 */
public final class SatcatCsvParser {
    private SatcatCsvParser() {}

    public static List<CatalogObject> parse(InputStream in, Instant ingestedAt) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String header = r.readLine();
        if (header == null) return List.of();
        Map<String, Integer> idx = new HashMap<>();
        String[] cols = header.split(",");
        for (int i = 0; i < cols.length; i++) idx.put(cols[i].trim(), i);
        List<CatalogObject> out = new ArrayList<>(70_000);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isBlank()) continue;
            String[] f = splitCsv(line);
            String id = get(f, idx, "NORAD_CAT_ID");
            if (id == null || id.isBlank()) continue;
            int norad;
            try { norad = Integer.parseInt(id.trim()); } catch (NumberFormatException e) { continue; }
            Double rcs = null;
            String rcsStr = get(f, idx, "RCS");
            if (rcsStr != null && !rcsStr.isBlank()) {
                try { rcs = Double.parseDouble(rcsStr); } catch (NumberFormatException ignored) { }
            }
            out.add(new CatalogObject(norad, get(f, idx, "OBJECT_NAME"), get(f, idx, "OBJECT_ID"),
                    get(f, idx, "OBJECT_TYPE"), get(f, idx, "OPS_STATUS_CODE"), get(f, idx, "OWNER"),
                    blankToNull(get(f, idx, "LAUNCH_DATE")), get(f, idx, "LAUNCH_SITE"),
                    blankToNull(get(f, idx, "DECAY_DATE")), rcs, ingestedAt));
        }
        return out;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String get(String[] f, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        return i == null || i >= f.length ? null : f[i];
    }

    /** Minimal RFC-4180 split: handles quoted fields containing commas. */
    static String[] splitCsv(String line) {
        List<String> parts = new ArrayList<>(20);
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) { parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    static boolean hasAll(String header, String... cols) {
        List<String> h = Arrays.asList(header.split(","));
        return h.containsAll(Arrays.asList(cols));
    }
}
