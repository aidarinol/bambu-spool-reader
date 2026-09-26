package id.min3d.spoolreader;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Spool counts, stored privately on the phone (SharedPreferences). */
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

    private static final String PREF_QTY = "stock_qty";
    private static final String PREF_CUSTOM = "stock_custom";

    private final SharedPreferences qty, custom;

    public StockStore(Context ctx) {
        qty = ctx.getSharedPreferences(PREF_QTY, Context.MODE_PRIVATE);
        custom = ctx.getSharedPreferences(PREF_CUSTOM, Context.MODE_PRIVATE);
    }

    public int get(String key) {
        return qty.getInt(key, 0);
    }

    /** Overwrites the old count. */
    public void set(String key, int n) {
        qty.edit().putInt(key, Math.max(0, n)).apply();
    }

    /** Remembers a spool that is not in the colour table so it still shows up in the stock list. */
    public void rememberCustom(String key, String type, String name, List<String> colors) {
        custom.edit().putString(key, type + "\t" + name + "\t" + join(colors)).apply();
    }

    /** Sets every count to 0 (the list of filaments itself stays). */
    public void resetAll() {
        qty.edit().clear().apply();
    }

    /** All filaments (table + remembered unknowns), grouped by type, sorted by name. */
    public TreeMap<String, List<Item>> grouped(ColorDb db) {
        TreeMap<String, List<Item>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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
        for (List<Item> l : groups.values()) {
            Collections.sort(l, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
        }
        return groups;
    }

    private static void add(TreeMap<String, List<Item>> groups, Item it) {
        List<Item> l = groups.get(it.type);
        if (l == null) {
            l = new ArrayList<>();
            groups.put(it.type, l);
        }
        l.add(it);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }
}
