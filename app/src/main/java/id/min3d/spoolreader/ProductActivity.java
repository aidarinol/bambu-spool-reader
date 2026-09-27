package id.min3d.spoolreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static id.min3d.spoolreader.MainActivity.ACCENT;
import static id.min3d.spoolreader.MainActivity.BG;
import static id.min3d.spoolreader.MainActivity.CARD;
import static id.min3d.spoolreader.MainActivity.FG;
import static id.min3d.spoolreader.MainActivity.LINE;
import static id.min3d.spoolreader.MainActivity.MUTED;
import static id.min3d.spoolreader.MainActivity.WARN;

/**
 * 3D product viewer: rotate the product freely and colour each part with a filament that is in stock (qty > 0).
 */
public class ProductActivity extends Activity {

    public static final String EXTRA_ID = "product_id";
    private static final int STAGE = Color.rgb(46, 46, 52);

    /** A product the viewer can show. slotMaterials[i] is the ModelAsset material coloured by slot i. */
    public static final class Product {
        public final String id, title, description, asset;
        public final String[] slotLabels;
        final int[] slotMaterials;

        Product(String id, String title, String description, String asset, String[] slotLabels, int[] slotMaterials) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.asset = asset;
            this.slotLabels = slotLabels;
            this.slotMaterials = slotMaterials;
        }
    }

    public static final Product[] PRODUCTS = {
            new Product("keychain_clicker", "Keychain Clicker",
                    "\"MIN3D\" keycap clicker keychain: 1 top base with hanger ring + 4 middle bases, 5 MX switches.",
                    "models/keychain_clicker.m3d",
                    new String[]{"Base", "Keycap", "Letters / numbers"},
                    new int[]{ModelAsset.MAT_BASE, ModelAsset.MAT_KEYCAP, ModelAsset.MAT_LEGEND}),
    };

    private Product product;
    private ColorDb db;
    private StockStore stock;
    private SharedPreferences prefs;
    private ModelView viewer;
    private LinearLayout slotsBox;
    /** In-stock filaments (qty > 0) by stock key, in stock-page order. */
    private final LinkedHashMap<String, StockStore.Item> inStock = new LinkedHashMap<>();
    private StockStore.Item[] chosen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        String id = getIntent().getStringExtra(EXTRA_ID);
        for (Product p : PRODUCTS) if (p.id.equals(id)) product = p;
        if (product == null) product = PRODUCTS[0];
        prefs = getSharedPreferences("product_colors", MODE_PRIVATE);
        stock = new StockStore(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(16), dp(8));
        TextView back = text("‹", 30, FG, false);
        back.setPadding(dp(12), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(text(product.title, 20, FG, true));
        root.addView(bar);

        ModelAsset model;
        String vs, fs;
        try {
            db = new ColorDb(getAssets().open("colors.tsv"));
            model = ModelAsset.read(getAssets().open(product.asset));
            vs = readText("shaders/model.vert");
            fs = readText("shaders/model.frag");
        } catch (IOException e) {
            TextView err = text("Could not load the 3D model: " + e.getMessage(), 16, MainActivity.ERR, false);
            err.setPadding(dp(20), dp(20), dp(20), dp(20));
            root.addView(err);
            return;
        }

        FrameLayout stage = new FrameLayout(this);
        viewer = new ModelView(this, model, vs, fs, STAGE);
        stage.addView(viewer);
        TextView hint = text("Drag to rotate · pinch to zoom · double-tap to reset", 11, MUTED, false);
        hint.setPadding(dp(12), dp(8), dp(12), dp(8));
        stage.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        root.addView(stage, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f));

        LinearLayout presets = new LinearLayout(this);
        presets.setPadding(dp(16), dp(10), dp(16), dp(2));
        addPreset(presets, "3/4", ModelView.Preset.DEFAULT);
        addPreset(presets, "Front", ModelView.Preset.FRONT);
        addPreset(presets, "Side", ModelView.Preset.SIDE);
        addPreset(presets, "Back", ModelView.Preset.BACK);
        root.addView(presets);

        ScrollView scroll = new ScrollView(this);
        slotsBox = new LinearLayout(this);
        slotsBox.setOrientation(LinearLayout.VERTICAL);
        slotsBox.setPadding(dp(16), dp(8), dp(16), dp(20));
        scroll.addView(slotsBox);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (viewer == null) return;
        viewer.onResume();
        loadStockAndChoices(); // stock may have changed on the Stock page
        renderSlots();
    }

    @Override
    protected void onPause() {
        if (viewer != null) viewer.onPause();
        super.onPause();
    }

    // ---------------- colours ----------------

    private void loadStockAndChoices() {
        inStock.clear();
        for (List<StockStore.Item> l : stock.grouped(db).values())
            for (StockStore.Item it : l) if (it.qty > 0) inStock.put(it.key, it);

        int n = product.slotLabels.length;
        chosen = new StockStore.Item[n];
        for (int i = 0; i < n; i++) chosen[i] = inStock.get(prefs.getString(prefKey(i), ""));
        if (!inStock.isEmpty()) fillDefaults();
        for (int i = 0; i < n; i++) applyLook(i);
    }

    /** Unset slots: keycap = lightest colour, letters = darkest, base = most-stocked colour not used yet. */
    private void fillDefaults() {
        List<StockStore.Item> all = new ArrayList<>(inStock.values());
        StockStore.Item light = all.get(0), dark = all.get(0);
        for (StockStore.Item it : all) {
            if (luma(it) > luma(light)) light = it;
            if (luma(it) < luma(dark)) dark = it;
        }
        for (int i = 0; i < chosen.length; i++) {
            if (chosen[i] != null) continue;
            int mat = product.slotMaterials[i];
            if (mat == ModelAsset.MAT_KEYCAP) chosen[i] = light;
            else if (mat == ModelAsset.MAT_LEGEND) chosen[i] = dark;
        }
        for (int i = 0; i < chosen.length; i++) {
            if (chosen[i] != null) continue;
            StockStore.Item pick = all.get(0);
            for (StockStore.Item it : all)
                if (!isChosen(it)) {
                    pick = it;
                    break;
                }
            chosen[i] = pick;
        }
    }

    private boolean isChosen(StockStore.Item it) {
        for (StockStore.Item c : chosen) if (c == it) return true;
        return false;
    }

    private void choose(int slot, StockStore.Item it) {
        chosen[slot] = it;
        prefs.edit().putString(prefKey(slot), it.key).apply();
        applyLook(slot);
        renderSlots();
    }

    private void applyLook(int slot) {
        StockStore.Item it = chosen[slot];
        int mat = product.slotMaterials[slot];
        if (it == null) {
            // nothing in stock: neutral preview colours
            int[] fallback = {0x3A3A40, 0xE8E8E8, 0x1A1A1A};
            viewer.setLook(mat, new ModelView.Look(fallback[Math.min(slot, 2)], 0.2f, 32f));
            return;
        }
        viewer.setLook(mat, look(it));
    }

    /** Surface finish from the filament type name. */
    static ModelView.Look look(StockStore.Item it) {
        int c = MainActivity.argb(it.colors.isEmpty() ? "#808080FF" : it.colors.get(0)) & 0xFFFFFF;
        String t = it.type.toLowerCase(Locale.ROOT);
        if (t.contains("silk")) return new ModelView.Look(c, 0.65f, 80f);
        if (t.contains("matte") || t.contains("wood") || t.contains("marble")) return new ModelView.Look(c, 0.04f, 10f);
        if (t.contains("petg") || t.contains("translucent")) return new ModelView.Look(c, 0.38f, 56f);
        return new ModelView.Look(c, 0.22f, 32f);
    }

    private static float luma(StockStore.Item it) {
        int c = MainActivity.argb(it.colors.isEmpty() ? "#808080FF" : it.colors.get(0));
        return 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c);
    }

    private String prefKey(int slot) {
        return product.id + "." + slot;
    }

    // ---------------- UI ----------------

    private void renderSlots() {
        slotsBox.removeAllViews();
        if (inStock.isEmpty()) {
            LinearLayout c = card();
            c.addView(text("No filament in stock", 16, WARN, true));
            TextView t = text("Colours come from the Stock page (only filaments with at least 1 spool). "
                    + "Add your spools there, then come back.", 13, MUTED, false);
            t.setPadding(0, dp(4), 0, 0);
            c.addView(t);
            slotsBox.addView(c);
            return;
        }
        for (int i = 0; i < product.slotLabels.length; i++) {
            final int slot = i;
            StockStore.Item it = chosen[i];
            LinearLayout c = card();
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.addView(swatch(it, dp(40)));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(dp(14), 0, dp(8), 0);
            txt.addView(text(product.slotLabels[i], 12, MUTED, false));
            txt.addView(text(it.name, 16, FG, true));
            String sub = it.type + " · " + it.qty + (it.qty == 1 ? " spool" : " spools");
            if (it.colors.size() > 1) sub += " · multi-colour: preview shows the first colour";
            txt.addView(text(sub, 12, MUTED, false));
            c.addView(txt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            c.addView(text("Change", 13, ACCENT, true));
            c.setOnClickListener(v -> pick(slot));
            slotsBox.addView(c);
            View gap = new View(this);
            slotsBox.addView(gap, new LinearLayout.LayoutParams(1, dp(8)));
        }
        Button copy = new Button(this);
        copy.setText("Copy colour list");
        copy.setAllCaps(false);
        copy.setTextColor(FG);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(CARD);
        g.setStroke(dp(1), LINE);
        copy.setBackground(g);
        copy.setOnClickListener(v -> copySummary());
        slotsBox.addView(copy);
        TextView note = text("Only filaments with stock > 0 are offered. Colours on screen are approximate.", 11, MUTED, false);
        note.setPadding(0, dp(10), 0, 0);
        slotsBox.addView(note);
    }

    private void pick(final int slot) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(12));
        final AlertDialog[] dlg = new AlertDialog[1];
        Map<String, List<StockStore.Item>> byType = new LinkedHashMap<>();
        for (StockStore.Item it : inStock.values()) {
            List<StockStore.Item> l = byType.get(it.type);
            if (l == null) byType.put(it.type, l = new ArrayList<>());
            l.add(it);
        }
        for (Map.Entry<String, List<StockStore.Item>> e : byType.entrySet()) {
            TextView h = text(Compat.label(e.getKey()), 13, MUTED, true);
            h.setPadding(0, dp(12), 0, dp(4));
            list.addView(h);
            for (final StockStore.Item it : e.getValue()) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(8), dp(4), dp(8));
                row.addView(swatch(it, dp(30)));
                TextView name = text(it.name, 16, FG, it == chosen[slot]);
                name.setPadding(dp(12), 0, dp(8), 0);
                row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(text(it == chosen[slot] ? "✓" : it.qty + "×", 14, it == chosen[slot] ? ACCENT : MUTED, true));
                row.setOnClickListener(v -> {
                    choose(slot, it);
                    if (dlg[0] != null) dlg[0].dismiss();
                });
                list.addView(row);
            }
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        dlg[0] = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(product.slotLabels[slot] + " colour")
                .setView(sv)
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copySummary() {
        StringBuilder sb = new StringBuilder(product.title).append(" (MIN3D)");
        for (int i = 0; i < chosen.length; i++)
            sb.append("\n").append(product.slotLabels[i]).append(": ").append(chosen[i].type).append(" ").append(chosen[i].name);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Colours", sb.toString()));
        Toast.makeText(this, "Colour list copied", Toast.LENGTH_SHORT).show();
    }

    private void addPreset(LinearLayout row, String label, final ModelView.Preset p) {
        TextView b = text(label, 13, FG, false);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(CARD);
        g.setStroke(dp(1), LINE);
        b.setBackground(g);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setOnClickListener(v -> viewer.showPreset(p));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(row.getChildCount() == 0 ? 0 : dp(6), 0, 0, 0);
        row.addView(b, lp);
    }

    private View swatch(StockStore.Item it, int size) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(MainActivity.argb(it.colors.isEmpty() ? "#808080FF" : it.colors.get(0)) | 0xFF000000);
        g.setStroke(dp(1), Color.rgb(82, 82, 91));
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        c.setBackground(g);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        return c;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private String readText(String asset) throws IOException {
        try (InputStream in = getAssets().open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
