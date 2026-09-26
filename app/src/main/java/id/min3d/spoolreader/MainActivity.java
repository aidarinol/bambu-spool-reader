package id.min3d.spoolreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Min3D Studio: read-only Bambu Lab spool RFID reader + simple spool stock list.
 * No INTERNET permission, nothing is ever written to the tag.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final int REQ_EXPORT = 42;

    private static final int BG = Color.rgb(24, 24, 27);
    private static final int CARD = Color.rgb(39, 39, 42);
    private static final int LINE = Color.rgb(63, 63, 70);
    private static final int FG = Color.rgb(244, 244, 245);
    private static final int MUTED = Color.rgb(161, 161, 170);
    private static final int ACCENT = Color.rgb(34, 197, 94);
    private static final int WARN = Color.rgb(250, 204, 21);
    private static final int ERR = Color.rgb(248, 113, 113);

    private NfcAdapter nfc;
    private ColorDb db;
    private StockStore stock;

    private LinearLayout content;
    private ScrollView scroll;
    private Button tabScan, tabStock;
    private TextView status;
    private boolean nfcWasOff;

    private boolean onStockPage;
    private Result lastResult;
    private String stockFilter = "";

    // ---------------- lifecycle ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        try {
            db = new ColorDb(getAssets().open("colors.tsv"));
        } catch (IOException e) {
            db = null;
        }
        stock = new StockStore(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(12), dp(8));
        TextView brand = text("Min3D Studio", 20, FG, true);
        bar.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabScan = tab("Scan");
        tabStock = tab("Stock");
        tabScan.setOnClickListener(v -> showScanPage());
        tabStock.setOnClickListener(v -> showStockPage());
        bar.addView(tabScan);
        bar.addView(tabStock);
        root.addView(bar);

        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        nfc = NfcAdapter.getDefaultAdapter(this);
        showScanPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfc == null) {
            if (!onStockPage) showMessage("This phone has no NFC.", ERR, false);
            return;
        }
        if (!nfc.isEnabled()) {
            if (!onStockPage) showMessage("NFC is turned off. Turn it on, then come back to the app.", WARN, true);
            nfcWasOff = true;
            return;
        }
        if (nfcWasOff) {
            nfcWasOff = false;
            if (!onStockPage) showScanPage();
        }
        Bundle opts = new Bundle();
        opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
        nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, opts);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableReaderMode(this);
    }

    @Override
    public void onBackPressed() {
        if (onStockPage) showScanPage();
        else super.onBackPressed();
    }

    // ---------------- NFC ----------------

    /** Called on a binder thread. */
    @Override
    public void onTagDiscovered(Tag tag) {
        runOnUiThread(() -> {
            if (!onStockPage) setStatus("Reading tag… keep the phone still", MUTED);
        });
        final Result r = readTag(tag);
        runOnUiThread(() -> {
            lastResult = r;
            showScanPage();
        });
    }

    private static final class Result {
        SpoolData data;
        String error;
    }

    private Result readTag(Tag tag) {
        Result res = new Result();
        if (!Arrays.asList(tag.getTechList()).contains(MifareClassic.class.getName())) {
            res.error = "This tag is not MIFARE Classic, so it is not a Bambu Lab spool tag.";
            return res;
        }
        MifareClassic mc = MifareClassic.get(tag);
        byte[] uid = tag.getId();
        byte[][] keys = SpoolData.deriveKeys(uid);
        byte[][] blocks = new byte[64][];
        try {
            mc.connect();
            mc.setTimeout(1000);
            int lastSector = -1;
            boolean authed = false;
            for (int b : SpoolData.BLOCKS) {
                int sector = b / 4;
                if (sector != lastSector) {
                    authed = mc.authenticateSectorWithKeyA(sector, keys[sector]);
                    lastSector = sector;
                    if (!authed && sector <= 1) {
                        res.error = "Key mismatch. This is probably not a genuine Bambu Lab tag (or it is a third-party tag).";
                        return res;
                    }
                }
                if (authed) blocks[b] = mc.readBlock(b);
            }
            res.data = SpoolData.parse(uid, blocks);
        } catch (IOException e) {
            res.error = "Read interrupted. Hold the phone against the spool again for 1–2 seconds without moving.";
        } catch (RuntimeException e) {
            res.error = "Could not read the tag: " + e.getClass().getSimpleName();
        } finally {
            try {
                mc.close();
            } catch (IOException ignored) {
            }
        }
        return res;
    }

    // ---------------- Scan page ----------------

    private void showScanPage() {
        onStockPage = false;
        highlightTabs();
        if (lastResult == null) showIdle();
        else if (lastResult.error != null) showMessage(lastResult.error, ERR, false);
        else renderSpool(lastResult.data);
        scroll.scrollTo(0, 0);
    }

    private void showIdle() {
        content.removeAllViews();
        status = text("Hold the back of your phone against the side of the spool (near the label / centre hub).", 16, MUTED, false);
        content.addView(status);
        if (db != null) {
            TextView info = text(db.size() + " colours in the database (official Bambu Studio table + community data). "
                    + "This app only reads tags, never writes to them, and has no internet access.", 12, MUTED, false);
            info.setPadding(0, dp(24), 0, 0);
            content.addView(info);
        }
    }

    private void showMessage(String msg, int color, boolean nfcButton) {
        content.removeAllViews();
        status = text(msg, 16, color, false);
        content.addView(status);
        if (nfcButton) {
            Button b = button("Open NFC settings", false);
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
            content.addView(b);
        }
    }

    private void setStatus(String s, int color) {
        if (status != null) {
            status.setText(s);
            status.setTextColor(color);
        }
    }

    private void renderSpool(final SpoolData d) {
        final ColorDb.Match m = db == null ? null : db.lookup(d);
        content.removeAllViews();

        content.addView(swatches(d.colors, dp(90), dp(14)));

        final String name = m != null ? m.name : "Unknown colour";
        TextView big = text(name, 30, m != null ? FG : WARN, true);
        big.setPadding(0, dp(16), 0, dp(2));
        content.addView(big);

        final String type = m != null ? m.filaType
                : (!d.detailedType.isEmpty() ? d.detailedType : (!d.filamentType.isEmpty() ? d.filamentType : ColorDb.OTHER_TYPE));
        content.addView(text(!d.detailedType.isEmpty() ? d.detailedType : type, 18, MUTED, false));

        String conf;
        int confColor;
        if (m == null) {
            conf = "Not in the database. Compare the variant code & hex below with the Bambu catalogue.";
            confColor = WARN;
        } else if ("code".equals(m.how)) {
            conf = "✓ Matched by variant code in the official Bambu Studio table";
            confColor = ACCENT;
        } else if ("hex".equals(m.how)) {
            conf = "✓ Matched by colour hex in the official Bambu Studio table";
            confColor = ACCENT;
        } else if ("hex1".equals(m.how)) {
            conf = "≈ Only the first colour matched the official table. Please double-check.";
            confColor = WARN;
        } else {
            conf = "≈ From community data (not the official table). Please double-check.";
            confColor = WARN;
        }
        TextView c = text(conf, 13, confColor, false);
        c.setPadding(0, dp(8), 0, dp(16));
        content.addView(c);

        // ---- stock input ----
        final String key;
        if (m != null) {
            key = m.row.stockKey;
        } else {
            key = "X|" + d.materialId + "|" + d.variantId;
        }
        LinearLayout stockCard = card();
        TextView sl = text("Stock", 14, MUTED, true);
        stockCard.addView(sl);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, 0);
        final EditText qty = new EditText(this);
        qty.setInputType(InputType.TYPE_CLASS_NUMBER);
        qty.setImeOptions(EditorInfo.IME_ACTION_DONE);
        qty.setSingleLine(true);
        qty.setTextColor(FG);
        qty.setTextSize(22);
        qty.setGravity(Gravity.CENTER);
        qty.setText(String.valueOf(stock.get(key)));
        qty.setSelectAllOnFocus(true);
        line.addView(qty, new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView unit = text("spool(s)", 16, FG, false);
        unit.setPadding(dp(10), 0, dp(10), 0);
        line.addView(unit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button save = button("Save", true);
        line.addView(save);
        stockCard.addView(line);
        TextView hint = text("Saving replaces the previous count.", 12, MUTED, false);
        hint.setPadding(0, dp(4), 0, 0);
        stockCard.addView(hint);
        content.addView(stockCard);

        final Runnable doSave = () -> {
            Integer n = parseQty(qty.getText().toString());
            if (n == null) {
                Toast.makeText(this, "Enter a whole number (0 or more).", Toast.LENGTH_SHORT).show();
                return;
            }
            if (m == null) stock.rememberCustom(key, type, "Unknown (" + d.variantId + ")", d.colors);
            stock.set(key, n);
            hideKeyboard(qty);
            qty.clearFocus();
            Toast.makeText(this, "Saved: " + n + " spool(s) of " + name + " (" + type + ")", Toast.LENGTH_SHORT).show();
        };
        save.setOnClickListener(v -> doSave.run());
        qty.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doSave.run();
                return true;
            }
            return false;
        });

        spacer(12);

        // ---- details ----
        LinearLayout info = card();
        row(info, "Variant code", d.variantId);
        row(info, "Material ID", d.materialId);
        row(info, "Colour (hex)", join(d.colors));
        if (d.weightG > 0) row(info, "Filament weight", d.weightG + " g");
        if (d.lengthM > 0) row(info, "Length", "≈ " + d.lengthM + " m");
        if (d.diameterMm > 0) row(info, "Diameter", String.format(Locale.ROOT, "%.2f mm", d.diameterMm));
        if (d.hotendMinC > 0) row(info, "Nozzle temp", d.hotendMinC + "–" + d.hotendMaxC + " °C");
        if (d.bedTempC > 0) row(info, "Bed temp", d.bedTempC + " °C");
        if (d.dryTempC > 0) row(info, "Drying", d.dryTempC + " °C, " + d.dryHours + " h");
        if (!d.productionDate.isEmpty()) row(info, "Produced", d.productionDate.replace('_', ' '));
        row(info, "Tag UID", d.uid);
        content.addView(info);

        status = text("Tap another spool to read it.", 13, MUTED, false);
        status.setPadding(0, dp(16), 0, 0);
        content.addView(status);
    }

    // ---------------- Stock page ----------------

    private void showStockPage() {
        onStockPage = true;
        highlightTabs();
        content.removeAllViews();
        if (db == null) {
            content.addView(text("Colour database could not be loaded.", 16, ERR, false));
            return;
        }
        TreeMap<String, List<StockStore.Item>> groups = stock.grouped(db);
        int total = 0, colours = 0;
        for (List<StockStore.Item> l : groups.values())
            for (StockStore.Item it : l)
                if (it.qty > 0) {
                    total += it.qty;
                    colours++;
                }

        content.addView(text("Filament stock", 24, FG, true));
        TextView sum = text(total + " spool(s) in stock across " + colours + " colour(s)", 14, MUTED, false);
        sum.setPadding(0, dp(2), 0, dp(12));
        content.addView(sum);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button reset = button("Reset to 0", false);
        reset.setTextColor(ERR);
        Button export = button("Export to PDF", true);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(0, 0, dp(8), 0);
        actions.addView(reset, half);
        actions.addView(export, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(actions);
        reset.setOnClickListener(v -> confirmReset());
        export.setOnClickListener(v -> startExport());

        final EditText search = new EditText(this);
        search.setHint("Search colour or type…");
        search.setHintTextColor(MUTED);
        search.setTextColor(FG);
        search.setSingleLine(true);
        search.setText(stockFilter);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(12), 0, dp(4));
        content.addView(search, sp);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        renderStockList(list, groups);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                stockFilter = s.toString().trim();
                renderStockList(list, stock.grouped(db));
            }
        });
    }

    private void renderStockList(LinearLayout list, TreeMap<String, List<StockStore.Item>> groups) {
        list.removeAllViews();
        String f = stockFilter.toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Map.Entry<String, List<StockStore.Item>> g : groups.entrySet()) {
            boolean typeHit = g.getKey().toLowerCase(Locale.ROOT).contains(f);
            int sum = 0;
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            int n = 0;
            for (final StockStore.Item it : g.getValue()) {
                sum += it.qty;
                if (!f.isEmpty() && !typeHit && !it.name.toLowerCase(Locale.ROOT).contains(f)) continue;
                rows.addView(stockRow(it));
                n++;
            }
            if (n == 0) continue;
            shown += n;
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setPadding(0, dp(18), 0, dp(6));
            head.addView(text(g.getKey(), 16, FG, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(text(sum + " spool(s)", 13, sum > 0 ? ACCENT : MUTED, true));
            list.addView(head);
            View div = new View(this);
            div.setBackgroundColor(LINE);
            list.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            list.addView(rows);
        }
        if (shown == 0) {
            TextView none = text("No matching filament.", 14, MUTED, false);
            none.setPadding(0, dp(16), 0, 0);
            list.addView(none);
        }
    }

    private View stockRow(final StockStore.Item it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(9), 0, dp(9));
        View sw = swatches(it.colors, dp(22), dp(5));
        r.addView(sw, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        mid.addView(text(it.name, 15, it.qty > 0 ? FG : MUTED, false));
        mid.addView(text(it.code, 11, MUTED, false));
        r.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text(String.valueOf(it.qty), 18, it.qty > 0 ? FG : MUTED, it.qty > 0));
        r.setOnClickListener(v -> editQty(it));
        return r;
    }

    private void editQty(final StockStore.Item it) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setText(String.valueOf(it.qty));
        in.setSelectAllOnFocus(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(it.name + " (" + it.type + ")")
                .setMessage("Spool(s) in stock. Saving replaces the previous count.")
                .setView(wrap)
                .setPositiveButton("Save", (dlg, w) -> {
                    Integer n = parseQty(in.getText().toString());
                    if (n == null) {
                        Toast.makeText(this, "Enter a whole number (0 or more).", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    stock.set(it.key, n);
                    int y = scroll.getScrollY();
                    showStockPage();
                    scroll.post(() -> scroll.scrollTo(0, y));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reset all stock to 0?")
                .setMessage("Every filament count will be set to 0. This cannot be undone.")
                .setPositiveButton("Reset to 0", (dlg, w) -> {
                    stock.resetAll();
                    Toast.makeText(this, "All stock reset to 0.", Toast.LENGTH_SHORT).show();
                    showStockPage();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startExport() {
        boolean any = false;
        for (List<StockStore.Item> l : stock.grouped(db).values())
            for (StockStore.Item it : l) if (it.qty > 0) any = true;
        if (!any) {
            Toast.makeText(this, "Nothing in stock to export.", Toast.LENGTH_SHORT).show();
            return;
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, "Min3D-Filament-Stock-" + date + ".pdf");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No file manager available to save the PDF.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String when = new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(new Date());
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("no stream");
            int n = new PdfExporter().write(out, stock.grouped(db), when);
            Toast.makeText(this, "PDF saved (" + n + " spool(s)).", Toast.LENGTH_SHORT).show();
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "application/pdf");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(view);
            } catch (Exception ignored) {
                // no PDF viewer installed; the file is saved anyway
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not save the PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- UI helpers ----------------

    private void highlightTabs() {
        styleTab(tabScan, !onStockPage);
        styleTab(tabStock, onStockPage);
    }

    private Button tab(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(4), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void styleTab(Button b, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(19));
        g.setColor(active ? FG : CARD);
        b.setBackground(g);
        b.setTextColor(active ? BG : FG);
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(primary ? ACCENT : CARD);
        if (!primary) g.setStroke(dp(1), LINE);
        b.setBackground(g);
        b.setTextColor(primary ? BG : FG);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        return b;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(CARD);
        cg.setCornerRadius(dp(12));
        card.setBackground(cg);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        return card;
    }

    private void spacer(int h) {
        View v = new View(this);
        content.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
    }

    /** One rounded block per colour, side by side. */
    private View swatches(List<String> colors, int height, int radius) {
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        int n = Math.max(1, colors.size());
        for (int i = 0; i < n; i++) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(i < colors.size() ? argb(colors.get(i)) : Color.GRAY);
            g.setCornerRadius(radius);
            g.setStroke(dp(1), Color.rgb(82, 82, 91));
            v.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
            if (i < n - 1) lp.setMargins(0, 0, height > dp(40) ? dp(8) : dp(2), 0);
            sw.addView(v, lp);
        }
        return sw;
    }

    private void row(LinearLayout parent, String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(5), 0, dp(5));
        TextView kt = text(k, 14, MUTED, false);
        TextView vt = text(v == null || v.isEmpty() ? "–" : v, 14, FG, false);
        vt.setGravity(Gravity.END);
        vt.setTextIsSelectable(true);
        r.addView(kt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(vt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        parent.addView(r);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private static Integer parseQty(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            return n >= 0 && n <= 9999 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(s);
        }
        return sb.toString();
    }

    /** "#RRGGBBAA" -> Android ARGB int. */
    private static int argb(String hex) {
        try {
            long v = Long.parseLong(hex.substring(1), 16);
            int r = (int) (v >> 24) & 0xFF, g = (int) (v >> 16) & 0xFF, b = (int) (v >> 8) & 0xFF, a = (int) v & 0xFF;
            if (a == 0) a = 0x40; // transparent filaments: still show a faint swatch
            return Color.argb(a, r, g, b);
        } catch (Exception e) {
            return Color.GRAY;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
