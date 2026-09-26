package id.min3d.spoolreader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Colour-name lookup. Table = Bambu Studio's official filaments_color_codes.json
 * plus a few community-sourced entries for old variant IDs the official table lacks.
 */
public final class ColorDb {

    public static final class Row {
        final String filaId, colorCode, filaType, name, source;
        final List<String> colors;

        Row(String[] f) {
            filaId = f[0];
            colorCode = f[1];
            colors = f[2].isEmpty() ? new ArrayList<>() : Arrays.asList(f[2].split(","));
            filaType = f[3];
            name = f[4];
            source = f[5];
        }
    }

    public static final class Match {
        public final String name;
        /** how it matched: "kode" | "hex" | "hex1" | "komunitas" */
        public final String how;
        public final String filaType;

        Match(String name, String how, String filaType) {
            this.name = name;
            this.how = how;
            this.filaType = filaType;
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
    }

    public int size() {
        return rows.size();
    }

    public Match lookup(SpoolData d) {
        String mid = d.materialId;
        int dash = d.variantId.indexOf('-');
        String code = dash >= 0 ? d.variantId.substring(dash + 1) : null;
        // 1) official: material ID + colour code from the variant ID (most precise)
        if (code != null) for (Row r : rows)
            if ("resmi".equals(r.source) && r.filaId.equals(mid) && r.colorCode.equals(code))
                return new Match(r.name, "kode", r.filaType);
        // 2) official: material ID + full colour list
        for (Row r : rows)
            if ("resmi".equals(r.source) && r.filaId.equals(mid) && sameColors(r.colors, d.colors))
                return new Match(r.name, "hex", r.filaType);
        // 3) official: material ID + first colour only
        if (!d.colors.isEmpty()) for (Row r : rows)
            if ("resmi".equals(r.source) && r.filaId.equals(mid) && !r.colors.isEmpty()
                    && r.colors.get(0).equalsIgnoreCase(d.colors.get(0)))
                return new Match(r.name, "hex1", r.filaType);
        // 4) community table keyed by full variant ID
        for (Row r : rows)
            if ("komunitas".equals(r.source) && r.filaId.equals(mid) && r.colorCode.equals("VID:" + d.variantId))
                return new Match(r.name, "komunitas", r.filaType);
        return null;
    }

    private static boolean sameColors(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equalsIgnoreCase(b.get(i))) return false;
        return true;
    }
}
