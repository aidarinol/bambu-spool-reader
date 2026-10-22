package id.min3d.spoolreader;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spool counts, stored privately on the phone (SharedPreferences), with CSV backup / restore. */
public final class StockStore {

    public static final class Item {
        public final String key, type, name, code;
        public final List<String> colors;
        public int qty;

        Item(String key, String type, String name, String code, List<String> colors, int qty) {
            this.key = key;
            this.type = type;
            this.name = name;
            this.code = code;
            this.colors = colors;
            this.qty = qty;
        }
    }

    /** Parsed backup, applied only after the user confirms. */
    public static final class Backup {
        final Map<String, Integer> qty = new HashMap<>();
        final Map<String, String> custom = new HashMap<>();
        public int entries, spools;
    }

    private static final String PREF_QTY = "stock_qty";
    private static final String PREF_CUSTOM = "stock_custom";
    /** Counts changed on this phone that the website database has not confirmed yet (key -> qty). */
    private static final String PREF_DIRTY = "stock_dirty";
    private static final String CSV_HEADER = "key,type,name,code,colors,qty";

    private final SharedPreferences qty, custom, dirty;

    /** Called after every change to the counts (used for the automatic website sync). */
    public Runnable onChange;

    private void changed() {
        if (onChange != null) onChange.run();
    }

    public StockStore(Context ctx) {
        qty = ctx.getSharedPreferences(PREF_QTY, Context.MODE_PRIVATE);
        custom = ctx.getSharedPreferences(PREF_CUSTOM, Context.MODE_PRIVATE);
        dirty = ctx.getSharedPreferences(PREF_DIRTY, Context.MODE_PRIVATE);
    }

    public int get(String key) {
        return qty.getInt(key, 0);
    }

    /** Overwrites the old count. */
    public void set(String key, int n) {
        n = Math.max(0, Math.min(9999, n));
        qty.edit().putInt(key, n).apply();
        dirty.edit().putInt(key, n).apply();
        changed();
    }

    /** Remembers a spool that is not in the colour table so it still shows up in the stock list. */
    public void rememberCustom(String key, String type, String name, List<String> colors) {
        custom.edit().putString(key, type + "\t" + name + "\t" + join(colors, ",")).apply();
    }

    /** Sets every count to 0 (the list of filaments itself stays). */
    public void resetAll() {
        SharedPreferences.Editor d = dirty.edit();
        for (Map.Entry<String, ?> e : qty.getAll().entrySet()) d.putInt(e.getKey(), 0);
        d.apply();
        qty.edit().clear().apply();
        changed();
    }

    /** All filaments (table + remembered unknowns), grouped by type; most-stocked types and colours first. */
    public LinkedHashMap<String, List<Item>> grouped(ColorDb db) {
        Map<String, List<Item>> groups = new HashMap<>();
        for (ColorDb.Row r : db.stockRows()) {
            add(groups, new Item(r.stockKey, r.filaType, r.name, r.filaId + " " + r.colorCode.replace("VID:", ""),
                    r.colors, get(r.stockKey)));
        }
        for (Map.Entry<String, ?> e : custom.getAll().entrySet()) {
            String[] f = String.valueOf(e.getValue()).split("\t", -1);
            if (f.length < 3) continue;
            List<String> cols = f[2].isEmpty() ? new ArrayList<String>() : Arrays.asList(f[2].split(","));
            String[] k = e.getKey().split("\\|");
            String code = k.length >= 3 ? k[1] + " " + k[2] : e.getKey();
            add(groups, new Item(e.getKey(), f[0], f[1], code, cols, get(e.getKey())));
        }
        final Map<String, Integer> totals = new HashMap<>();
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) totals.put(e.getKey(), total(e.getValue()));
        List<String> types = new ArrayList<>(groups.keySet());
        Collections.sort(types, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return compareTypes(a, totals.get(a), b, totals.get(b));
            }
        });
        LinkedHashMap<String, List<Item>> out = new LinkedHashMap<>();
        for (String t : types) {
            List<Item> l = groups.get(t);
            Collections.sort(l, ITEM_ORDER);
            out.put(t, l);
        }
        return out;
    }

    // ---------------- ordering (shared by list build and live re-sort) ----------------

    /** Colours: most spools first; equal counts (incl. 0) A-Z. */
    public static final Comparator<Item> ITEM_ORDER = new Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            if (a.qty != b.qty) return b.qty - a.qty;
            return a.name.compareToIgnoreCase(b.name);
        }
    };

    /** Types: most spools first; ties (incl. all 0-spool types) keep the Min3D order, then A-Z. */
    public static int compareTypes(String a, int totalA, String b, int totalB) {
        if (totalA != totalB) return totalB - totalA;
        int ra = Compat.rank(a), rb = Compat.rank(b);
        if (ra != rb) return ra - rb;
        return a.compareToIgnoreCase(b);
    }

    public static int total(List<Item> items) {
        int sum = 0;
        for (Item it : items) sum += it.qty;
        return sum;
    }

    // ---------------- CSV backup ----------------

    /** Writes every filament with stock > 0 plus all remembered unknown spools. @return spools written */
    public int writeBackup(OutputStream out, ColorDb db) throws IOException {
        StringBuilder sb = new StringBuilder(CSV_HEADER).append("\r\n");
        int spools = 0;
        for (List<Item> l : grouped(db).values()) {
            for (Item it : l) {
                boolean isCustom = custom.contains(it.key);
                if (it.qty <= 0 && !isCustom) continue;
                spools += it.qty;
                sb.append(csv(it.key)).append(',').append(csv(it.type)).append(',').append(csv(it.name)).append(',')
                        .append(csv(it.code)).append(',').append(csv(isCustom ? join(it.colors, " ") : "")).append(',')
                        .append(it.qty).append("\r\n");
            }
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        return spools;
    }

    public static Backup readBackup(InputStream in) throws IOException {
        Backup b = new Backup();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null) throw new IOException("The file is empty.");
        if (line.startsWith("﻿")) line = line.substring(1);
        if (!line.trim().equalsIgnoreCase(CSV_HEADER)) throw new IOException("This is not a Min3D Studio stock backup.");
        while ((line = r.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            List<String> f = parseCsv(line);
            if (f.size() < 6) throw new IOException("Broken line in backup: " + line);
            int n;
            try {
                n = Integer.parseInt(f.get(5).trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid quantity in backup: " + line);
            }
            if (n < 0 || n > 9999) throw new IOException("Invalid quantity in backup: " + line);
            String key = f.get(0);
            if (key.isEmpty()) continue;
            b.qty.put(key, n);
            if (key.startsWith("X|")) b.custom.put(key, f.get(1) + "\t" + f.get(2) + "\t" + f.get(4).trim().replace(' ', ','));
            b.entries++;
            b.spools += n;
        }
        return b;
    }

    /** Replaces the current stock with the backup (overwrite, like manual input). */
    public void apply(Backup b) {
        SharedPreferences.Editor d = dirty.edit();
        for (Map.Entry<String, ?> e : qty.getAll().entrySet()) d.putInt(e.getKey(), 0);
        for (Map.Entry<String, Integer> e : b.qty.entrySet()) d.putInt(e.getKey(), Math.max(0, e.getValue()));
        d.apply();
        SharedPreferences.Editor q = qty.edit().clear();
        for (Map.Entry<String, Integer> e : b.qty.entrySet()) if (e.getValue() > 0) q.putInt(e.getKey(), e.getValue());
        q.apply();
        SharedPreferences.Editor c = custom.edit();
        for (Map.Entry<String, String> e : b.custom.entrySet()) c.putString(e.getKey(), e.getValue());
        c.apply();
        changed();
    }

    // ---------------- two-way website sync ----------------

    /** One row as stored in the website database. */
    public static final class Remote {
        public final String key, type, name, code;
        public final List<String> colors;
        public final int qty;

        public Remote(String key, String type, String name, String code, List<String> colors, int qty) {
            this.key = key;
            this.type = type;
            this.name = name;
            this.code = code;
            this.colors = colors;
            this.qty = qty;
        }
    }

    /** Local changes not yet confirmed by the database. */
    public Map<String, Integer> dirty() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, ?> e : dirty.getAll().entrySet())
            if (e.getValue() instanceof Integer) out.put(e.getKey(), (Integer) e.getValue());
        return out;
    }

    /** Marks every filament as changed (used once, for the first full upload of the new two-way sync). */
    public void markAllDirty(ColorDb db) {
        SharedPreferences.Editor d = dirty.edit();
        for (List<Item> l : grouped(db).values()) for (Item it : l) if (it.qty > 0) d.putInt(it.key, it.qty);
        d.apply();
    }

    /** Forgets the pushed changes, unless the count was changed again while the upload was running. */
    public void confirmPushed(Map<String, Integer> pushed) {
        Map<String, Integer> now = dirty();
        SharedPreferences.Editor d = dirty.edit();
        for (Map.Entry<String, Integer> e : pushed.entrySet()) {
            Integer cur = now.get(e.getKey());
            if (cur != null && cur.intValue() == e.getValue().intValue()) d.remove(e.getKey());
        }
        d.apply();
    }

    /**
     * Takes the counts from the website database (changes made on min3dstudio.com, or on another phone).
     * Counts this phone changed but has not uploaded yet are kept. Does not trigger a new upload.
     * @return number of filaments whose count changed on this phone
     */
    public int applyRemote(List<Remote> rows, ColorDb db) {
        java.util.Set<String> known = new java.util.HashSet<>();
        if (db != null) for (ColorDb.Row r : db.stockRows()) known.add(r.stockKey);
        Map<String, Integer> pend = dirty();
        SharedPreferences.Editor q = qty.edit();
        SharedPreferences.Editor c = custom.edit();
        int changedN = 0;
        for (Remote r : rows) {
            if (r.key == null || r.key.isEmpty() || pend.containsKey(r.key)) continue;
            if (!known.contains(r.key) && !custom.contains(r.key))
                c.putString(r.key, r.type + "\t" + r.name + "\t" + join(r.colors, ","));
            int n = Math.max(0, Math.min(9999, r.qty));
            if (get(r.key) != n) {
                if (n == 0) q.remove(r.key);
                else q.putInt(r.key, n);
                changedN++;
            }
        }
        q.apply();
        c.apply();
        return changedN;
    }

    /** Item details for an upload (type, name, code, colours) by key. */
    public Map<String, Item> byKey(ColorDb db) {
        Map<String, Item> m = new HashMap<>();
        for (List<Item> l : grouped(db).values()) for (Item it : l) m.put(it.key, it);
        return m;
    }

    // ---------------- helpers ----------------

    private static void add(Map<String, List<Item>> groups, Item it) {
        List<Item> l = groups.get(it.type);
        if (l == null) {
            l = new ArrayList<>();
            groups.put(it.type, l);
        }
        l.add(it);
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    static String csv(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}
