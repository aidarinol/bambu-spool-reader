package id.min3d.spoolreader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Colour-name lookup. Table = Bambu Studio's official filaments_color_codes.json
 * plus a few community-sourced entries for old variant IDs the official table lacks.
 */
public final class ColorDb {

    public static final String OTHER_TYPE = "Other";

    public static final class Row {
        public final String filaId, colorCode, name, source;
        public String filaType;
        public final List<String> colors;
        /** Stock key this row counts towards (community duplicates point at the official row). */
        public String stockKey;

        Row(String[] f) {
            filaId = f[0];
            colorCode = f[1];
            colors = f[2].isEmpty() ? new ArrayList<String>() : Arrays.asList(f[2].split(","));
            filaType = f[3];
            name = f[4];
            source = f[5];
            stockKey = filaId + "|" + colorCode;
        }

        public boolean official() {
            return "resmi".equals(source);
        }
    }

    public static final class Match {
        public final Row row;
        public final String name;
        /** how it matched: "code" | "hex" | "hex1" | "community" */
        public final String how;
        public final String filaType;

        Match(Row row, String how) {
            this.row = row;
            this.name = row.name;
            this.how = how;
            this.filaType = row.filaType;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public ColorDb(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length >= 6) rows.add(new Row(f));
            }
        }
        // Fill missing types from official rows with the same material ID; alias community duplicates.
        Map<String, String> typeById = new HashMap<>();
        Map<String, Row> officialByIdName = new HashMap<>();
        for (Row r : rows) {
            if (!r.official()) continue;
            if (!r.filaType.isEmpty()) typeById.put(r.filaId, r.filaType);
            officialByIdName.put(r.filaId + "|" + r.name, r);
        }
        for (Row r : rows) {
            if (r.official()) continue;
            if (r.filaType.isEmpty()) {
                String t = typeById.get(r.filaId);
                r.filaType = t != null ? t : OTHER_TYPE;
            }
            Row off = officialByIdName.get(r.filaId + "|" + r.name);
            if (off != null) r.stockKey = off.stockKey;
        }
    }

    public int size() {
        return rows.size();
    }

    /** Rows that appear in the stock list (one per stock key). */
    public List<Row> stockRows() {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (r.stockKey.equals(r.filaId + "|" + r.colorCode)) out.add(r);
        return Collections.unmodifiableList(out);
    }

    public Match lookup(SpoolData d) {
        String mid = d.materialId;
        int dash = d.variantId.indexOf('-');
        String code = dash >= 0 ? d.variantId.substring(dash + 1) : null;
        // 1) official: material ID + colour code from the variant ID (most precise)
        if (code != null) for (Row r : rows)
            if (r.official() && r.filaId.equals(mid) && r.colorCode.equals(code))
                return new Match(r, "code");
        // 2) official: material ID + full colour list
        for (Row r : rows)
            if (r.official() && r.filaId.equals(mid) && sameColors(r.colors, d.colors))
                return new Match(r, "hex");
        // 3) official: material ID + first colour only
        if (!d.colors.isEmpty()) for (Row r : rows)
            if (r.official() && r.filaId.equals(mid) && !r.colors.isEmpty()
                    && r.colors.get(0).equalsIgnoreCase(d.colors.get(0)))
                return new Match(r, "hex1");
        // 4) community table keyed by full variant ID
        for (Row r : rows)
            if (!r.official() && r.filaId.equals(mid) && r.colorCode.equals("VID:" + d.variantId))
                return new Match(r, "community");
        return null;
    }

    private static boolean sameColors(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equalsIgnoreCase(b.get(i))) return false;
        return true;
    }
}
