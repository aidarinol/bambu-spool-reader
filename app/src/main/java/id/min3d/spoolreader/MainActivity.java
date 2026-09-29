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
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Min3D Studio: read-only Bambu Lab spool RFID reader + spool stock list.
 * No INTERNET permission, nothing is ever written to the tag.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final int REQ_EXPORT_PDF = 42;
    private static final int REQ_SAVE_BACKUP = 43;
    private static final int REQ_LOAD_BACKUP = 44;

    static final int BG = Color.rgb(24, 24, 27);
    static final int CARD = Color.rgb(39, 39, 42);
    static final int LINE = Color.rgb(63, 63, 70);
    static final int FG = Color.rgb(244, 244, 245);
    static final int MUTED = Color.rgb(161, 161, 170);
    static final int ACCENT = Color.rgb(34, 197, 94);
    static final int WARN = Color.rgb(250, 204, 21);
    static final int ERR = Color.rgb(248, 113, 113);
    static final int BLUE = Color.rgb(96, 165, 250);

    private NfcAdapter nfc;
    private ColorDb db;
    private StockStore stock;
    private WebSync web;

    private LinearLayout content;
    private ScrollView scroll;
    private Button tabScan, tabStock, tabProducts;
    private TextView status;
    private boolean nfcWasOff;

    private boolean onStockPage, onProductsPage;
    private Result lastResult;
    private String stockFilter = "";

    /** Stock page state: which type groups are open (all closed when the page is opened from the tab). */
    private final Set<String> expanded = new HashSet<>();
    private TextView summaryView;
    private final List<GroupView> groupViews = new ArrayList<>();

    private static final class GroupView {
        String type;
        List<StockStore.Item> items;
        TextView subtotal;
        LinearLayout block; // header + rows, moved as one unit when types re-sort
        LinearLayout rows;  // colour rows, re-sorted inside the block
    }

    private LinearLayout stockList;
    private static final long MOVE_MS = 450;

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
        web = new WebSync(this);
        stock.onChange = () -> web.schedule(stock, db);

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
        tabStock.setOnClickListener(v -> {
            expanded.clear(); // every dropdown starts closed
            stockFilter = "";
            showStockPage();
            scroll.scrollTo(0, 0);
        });
        tabProducts = tab("3D");
        tabProducts.setOnClickListener(v -> showProductsPage());
        bar.addView(tabScan);
        bar.addView(tabStock);
        bar.addView(tabProducts);
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
        if (web != null) web.retryIfPending(stock, db);
        if (nfc == null) {
            if (onScan()) showMessage("This phone has no NFC.", ERR, false);
            return;
        }
        if (!nfc.isEnabled()) {
            if (onScan()) showMessage("NFC is turned off. Turn it on, then come back to the app.", WARN, true);
            nfcWasOff = true;
            return;
        }
        if (nfcWasOff) {
            nfcWasOff = false;
            if (onScan()) showScanPage();
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
        if (!onScan()) showScanPage();
        else super.onBackPressed();
    }

    // ---------------- NFC ----------------

    /** Called on a binder thread. */
    @Override
    public void onTagDiscovered(Tag tag) {
        runOnUiThread(() -> {
            if (onScan()) setStatus("Reading tag… keep the phone still", MUTED);
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
        onProductsPage = false;
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
        LinearLayout typeLine = new LinearLayout(this);
        typeLine.setOrientation(LinearLayout.HORIZONTAL);
        typeLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView tt = text(!d.detailedType.isEmpty() ? d.detailedType : type, 18, MUTED, false);
        tt.setPadding(0, 0, dp(6), 0);
        typeLine.addView(tt);
        addCompatTags(typeLine, type);
        content.addView(typeLine);

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

        // ---- stock ----
        final String key = m != null ? m.row.stockKey : "X|" + d.materialId + "|" + d.variantId;
        final Runnable remember = () -> {
            if (m == null) stock.rememberCustom(key, type, "Unknown (" + d.variantId + ")", d.colors);
        };

        LinearLayout stockCard = card();
        stockCard.addView(text("Current stock", 14, MUTED, true));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(8), 0, 0);

        final EditText qty = new EditText(this);
        qty.setInputType(InputType.TYPE_CLASS_NUMBER);
        qty.setImeOptions(EditorInfo.IME_ACTION_DONE);
        qty.setSingleLine(true);
        qty.setTextColor(FG);
        qty.setTextSize(24);
        qty.setTypeface(Typeface.DEFAULT_BOLD);
        qty.setGravity(Gravity.CENTER);
        qty.setText(String.valueOf(stock.get(key)));
        qty.setSelectAllOnFocus(true);

        TextView minus = stepButton("−", 44);
        TextView plus = stepButton("+", 44);
        line.addView(minus);
        line.addView(qty, new LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT));
        line.addView(plus);
        TextView unit = text("spool(s)", 16, FG, false);
        unit.setPadding(dp(10), 0, dp(6), 0);
        line.addView(unit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button save = button("Save", true);
        line.addView(save);
        stockCard.addView(line);
        TextView hint = text("+1 / −1 are saved immediately. Typing a number and pressing Save replaces the old count.", 12, MUTED, false);
        hint.setPadding(0, dp(6), 0, 0);
        stockCard.addView(hint);
        content.addView(stockCard);

        final View.OnClickListener step = v -> {
            int n = Math.max(0, stock.get(key) + (v == plus ? 1 : -1));
            remember.run();
            stock.set(key, n);
            qty.setText(String.valueOf(stock.get(key)));
            qty.clearFocus();
            hideKeyboard(qty);
        };
        minus.setOnClickListener(step);
        plus.setOnClickListener(step);

        final Runnable doSave = () -> {
            Integer n = parseQty(qty.getText().toString());
            if (n == null) {
                Toast.makeText(this, "Enter a whole number (0 or more).", Toast.LENGTH_SHORT).show();
                return;
            }
            remember.run();
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

    // ---------------- 3D products page ----------------

    private void showProductsPage() {
        onStockPage = false;
        onProductsPage = true;
        highlightTabs();
        content.removeAllViews();
        content.addView(text("3D products", 24, FG, true));
        TextView sub = text("Turn a product around and try it in the filament colours you have in stock.", 14, MUTED, false);
        sub.setPadding(0, dp(2), 0, dp(12));
        content.addView(sub);
        for (final ProductActivity.Product p : ProductActivity.PRODUCTS) {
            LinearLayout c = card();
            c.addView(text(p.title, 18, FG, true));
            TextView d = text(p.description, 13, MUTED, false);
            d.setPadding(0, dp(2), 0, dp(10));
            c.addView(d);
            LinearLayout slots = new LinearLayout(this);
            slots.setOrientation(LinearLayout.HORIZONTAL);
            for (String s : p.slotLabels) slots.addView(chip(s, BLUE));
            c.addView(slots);
            Button open = button("Open 3D view", true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(12), 0, 0);
            c.addView(open, lp);
            open.setOnClickListener(v -> startActivity(new Intent(this, ProductActivity.class).putExtra(ProductActivity.EXTRA_ID, p.id)));
            c.setOnClickListener(v -> open.performClick());
            content.addView(c);
            spacer(12);
        }
        scroll.scrollTo(0, 0);
    }

    // ---------------- Stock page ----------------

    private void showStockPage() {
        onStockPage = true;
        onProductsPage = false;
        highlightTabs();
        content.removeAllViews();
        if (db == null) {
            content.addView(text("Colour database could not be loaded.", 16, ERR, false));
            return;
        }
        LinkedHashMap<String, List<StockStore.Item>> groups = stock.grouped(db);

        content.addView(text("Filament stock", 24, FG, true));
        summaryView = text("", 14, MUTED, false);
        summaryView.setPadding(0, dp(2), 0, dp(12));
        content.addView(summaryView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button reset = button("Reset to 0", false);
        reset.setTextColor(ERR);
        Button export = button("Export to PDF", true);
        row1.addView(reset, halfLeft());
        row1.addView(export, half());
        content.addView(row1);
        reset.setOnClickListener(v -> confirmReset());
        export.setOnClickListener(v -> startExportPdf());

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        Button saveB = button("Save backup", false);
        Button loadB = button("Load backup", false);
        row2.addView(saveB, halfLeft());
        row2.addView(loadB, half());
        content.addView(row2);
        saveB.setOnClickListener(v -> startSaveBackup());
        loadB.setOnClickListener(v -> startLoadBackup());

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(8), 0, 0);
        Button syncB = button("Sync website", true);
        Button tokenB = button("Website token", false);
        row3.addView(syncB, halfLeft());
        row3.addView(tokenB, half());
        content.addView(row3);
        final TextView webStatus = text(web.hasToken()
                ? (web.lastStatus().isEmpty() ? "min3dstudio.com: auto-sync on" : web.lastStatus())
                : "min3dstudio.com: set the website token to show in-stock colours on the website.", 11, MUTED, false);
        webStatus.setPadding(0, dp(4), 0, 0);
        content.addView(webStatus);
        syncB.setOnClickListener(v -> {
            if (!web.hasToken()) {
                askWebToken(webStatus);
                return;
            }
            webStatus.setText("Syncing to min3dstudio.com…");
            web.syncNow(stock, db, (ok, msg) -> webStatus.setText(web.lastStatus()));
        });
        tokenB.setOnClickListener(v -> askWebToken(webStatus));

        final EditText search = new EditText(this);
        search.setHint("Search colour or type…");
        search.setHintTextColor(MUTED);
        search.setTextColor(FG);
        search.setSingleLine(true);
        search.setText(stockFilter);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(12), 0, dp(2));
        content.addView(search, sp);

        TextView legend = text("Tags: printer can print this material. Amber A2L = needs a hardened-steel nozzle.", 11, MUTED, false);
        legend.setPadding(0, 0, 0, dp(4));
        content.addView(legend);

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

    private void renderStockList(LinearLayout list, LinkedHashMap<String, List<StockStore.Item>> groups) {
        stockList = list;
        list.removeAllViews();
        groupViews.clear();
        String f = stockFilter.toLowerCase(Locale.ROOT);
        boolean searching = !f.isEmpty();
        int shown = 0;
        for (Map.Entry<String, List<StockStore.Item>> g : groups.entrySet()) {
            final String type = g.getKey();
            boolean typeHit = Compat.label(type).toLowerCase(Locale.ROOT).contains(f);

            final GroupView gv = new GroupView();
            gv.type = type;
            gv.items = g.getValue();
            groupViews.add(gv);

            final LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            gv.rows = rows;
            int n = 0;
            for (final StockStore.Item it : g.getValue()) {
                if (searching && !typeHit && !it.name.toLowerCase(Locale.ROOT).contains(f)) continue;
                rows.addView(stockRow(it, gv));
                n++;
            }
            if (n == 0) continue;
            shown += n;

            boolean open = searching || expanded.contains(type);
            rows.setVisibility(open ? View.VISIBLE : View.GONE);

            // header bar (dropdown)
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(12), dp(12), dp(12), dp(12));
            GradientDrawable hb = new GradientDrawable();
            hb.setColor(CARD);
            hb.setCornerRadius(dp(10));
            head.setBackground(hb);
            final TextView chevron = text(open ? "▾" : "▸", 16, MUTED, true);
            chevron.setPadding(0, 0, dp(10), 0);
            head.addView(chevron);

            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.addView(text(Compat.label(type), 16, FG, true));
            LinearLayout tags = new LinearLayout(this);
            tags.setOrientation(LinearLayout.HORIZONTAL);
            tags.setPadding(0, dp(4), 0, 0);
            if (addCompatTags(tags, type)) mid.addView(tags);
            head.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            gv.subtotal = text("", 13, MUTED, true);
            head.addView(gv.subtotal);
            updateSubtotal(gv);

            head.setOnClickListener(v -> {
                boolean nowOpen = rows.getVisibility() != View.VISIBLE;
                rows.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
                chevron.setText(nowOpen ? "▾" : "▸");
                if (nowOpen) expanded.add(type);
                else expanded.remove(type);
            });

            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, dp(8), 0, 0);
            block.addView(head);
            block.addView(rows);
            block.setTag(gv);
            gv.block = block;
            list.addView(block);
        }
        if (shown == 0) {
            TextView none = text("No matching filament.", 14, MUTED, false);
            none.setPadding(0, dp(16), 0, 0);
            list.addView(none);
        }
        updateSummary();
    }

    private View stockRow(final StockStore.Item it, final GroupView gv) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(8), 0, dp(8));
        r.setTag(it);
        View sw = swatches(it.colors, dp(22), dp(5));
        r.addView(sw, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(12), 0, dp(8), 0);
        final TextView name = text(it.name, 15, it.qty > 0 ? FG : MUTED, false);
        mid.addView(name);
        mid.addView(text(it.code, 11, MUTED, false));
        r.addView(mid, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView minus = stepButton("−", 34);
        final TextView plus = stepButton("+", 34);
        final TextView q = text(String.valueOf(it.qty), 18, it.qty > 0 ? FG : MUTED, it.qty > 0);
        q.setGravity(Gravity.CENTER);
        r.addView(minus);
        r.addView(q, new LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(plus);

        final Runnable refresh = () -> {
            q.setText(String.valueOf(it.qty));
            q.setTextColor(it.qty > 0 ? FG : MUTED);
            q.setTypeface(it.qty > 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            name.setTextColor(it.qty > 0 ? FG : MUTED);
            updateSubtotal(gv);
            updateSummary();
            resortAnimated(gv, r);
        };
        View.OnClickListener step = v -> {
            int before = it.qty;
            it.qty = Math.max(0, it.qty + (v == plus ? 1 : -1));
            if (it.qty == before) return;
            stock.set(it.key, it.qty);
            refresh.run();
        };
        minus.setOnClickListener(step);
        plus.setOnClickListener(step);
        q.setOnClickListener(v -> editQty(it, refresh));
        return r;
    }

    private void updateSubtotal(GroupView gv) {
        if (gv.subtotal == null) return;
        int sum = StockStore.total(gv.items);
        gv.subtotal.setText(sum + " spool(s)");
        gv.subtotal.setTextColor(sum > 0 ? ACCENT : MUTED);
    }

    private void updateSummary() {
        if (summaryView == null) return;
        int total = 0, colours = 0;
        for (GroupView gv : groupViews)
            for (StockStore.Item it : gv.items)
                if (it.qty > 0) {
                    total += it.qty;
                    colours++;
                }
        summaryView.setText(total + " spool(s) in stock across " + colours + " colour(s)");
    }

    // ---------------- live re-sort with smooth movement (FLIP) ----------------

    /** Re-sorts the colours inside the changed type and the types in the list, animating every move. */
    private void resortAnimated(GroupView gv, final View changedRow) {
        Collections.sort(gv.items, StockStore.ITEM_ORDER);

        // colours inside this type
        List<View> rowViews = children(gv.rows);
        Collections.sort(rowViews, new java.util.Comparator<View>() {
            @Override
            public int compare(View a, View b) {
                return StockStore.ITEM_ORDER.compare((StockStore.Item) a.getTag(), (StockStore.Item) b.getTag());
            }
        });
        boolean rowsMoved = flipReorder(gv.rows, rowViews);

        // types in the list
        boolean blocksMoved = false;
        if (stockList != null) {
            List<View> blocks = new ArrayList<>();
            List<View> tail = new ArrayList<>();
            for (View v : children(stockList)) {
                if (v.getTag() instanceof GroupView) blocks.add(v);
                else tail.add(v);
            }
            Collections.sort(blocks, new java.util.Comparator<View>() {
                @Override
                public int compare(View a, View b) {
                    GroupView ga = (GroupView) a.getTag(), gb = (GroupView) b.getTag();
                    return StockStore.compareTypes(ga.type, StockStore.total(ga.items), gb.type, StockStore.total(gb.items));
                }
            });
            blocks.addAll(tail);
            blocksMoved = flipReorder(stockList, blocks);
        }

        flash(changedRow);
        if (rowsMoved || blocksMoved) {
            // after the slide, make sure the row you touched is still on screen
            scroll.postDelayed(() -> keepVisible(changedRow), MOVE_MS + 30);
        }
    }

    private static List<View> children(LinearLayout parent) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) out.add(parent.getChildAt(i));
        return out;
    }

    /**
     * FLIP: remember where each child is now (including any running slide), put the children in the new
     * order, then offset each one back to where it was and slide it to its new place.
     * @return true if the order actually changed
     */
    private boolean flipReorder(final LinearLayout parent, List<View> newOrder) {
        boolean changed = false;
        for (int i = 0; i < newOrder.size(); i++) if (parent.getChildAt(i) != newOrder.get(i)) changed = true;
        if (!changed) return false;

        final java.util.Map<View, Float> oldY = new java.util.HashMap<>();
        for (View v : newOrder) {
            v.animate().cancel();
            oldY.put(v, v.getTop() + v.getTranslationY());
        }
        parent.removeAllViews();
        for (View v : newOrder) parent.addView(v);

        parent.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                parent.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View v = parent.getChildAt(i);
                    Float from = oldY.get(v);
                    if (from == null) continue;
                    float dy = from - v.getTop();
                    if (Math.abs(dy) < 1f) {
                        v.setTranslationY(0f);
                        continue;
                    }
                    v.setTranslationY(dy);
                    v.animate().translationY(0f).setDuration(MOVE_MS)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
                }
                return true;
            }
        });
        return true;
    }

    /** Brief green glow on the row that was changed, fading out. */
    private void flash(View row) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(Color.argb(70, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT)));
        row.setBackground(g);
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofInt(g, "alpha", 255, 0);
        a.setStartDelay(MOVE_MS);
        a.setDuration(700);
        a.start();
    }

    private void keepVisible(View row) {
        int[] rowLoc = new int[2], scrollLoc = new int[2];
        row.getLocationOnScreen(rowLoc);
        scroll.getLocationOnScreen(scrollLoc);
        int top = rowLoc[1] - scrollLoc[1];
        int bottom = top + row.getHeight();
        int margin = dp(24);
        if (top < margin) scroll.smoothScrollBy(0, top - margin);
        else if (bottom > scroll.getHeight() - margin) scroll.smoothScrollBy(0, bottom - scroll.getHeight() + margin);
    }

    private void editQty(final StockStore.Item it, final Runnable refresh) {
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
                    it.qty = n;
                    stock.set(it.key, n);
                    refresh.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reset all stock to 0?")
                .setMessage("Every filament count will be set to 0. This cannot be undone. Tip: use Save backup first.")
                .setPositiveButton("Reset to 0", (dlg, w) -> {
                    stock.resetAll();
                    Toast.makeText(this, "All stock reset to 0.", Toast.LENGTH_SHORT).show();
                    refreshStockPageKeepingScroll();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshStockPageKeepingScroll() {
        final int y = scroll.getScrollY();
        showStockPage();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private boolean anyStock() {
        for (List<StockStore.Item> l : stock.grouped(db).values())
            for (StockStore.Item it : l) if (it.qty > 0) return true;
        return false;
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    private void startExportPdf() {
        if (!anyStock()) {
            Toast.makeText(this, "Nothing in stock to export.", Toast.LENGTH_SHORT).show();
            return;
        }
        createDocument("application/pdf", "Min3D-Filament-Stock-" + today() + ".pdf", REQ_EXPORT_PDF);
    }

    private void startSaveBackup() {
        createDocument("text/csv", "Min3D-Stock-Backup-" + today() + ".csv", REQ_SAVE_BACKUP);
    }

    private void startLoadBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain",
                "application/csv", "application/vnd.ms-excel", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_LOAD_BACKUP);
        } catch (Exception e) {
            Toast.makeText(this, "No file manager available.", Toast.LENGTH_LONG).show();
        }
    }

    private void createDocument(String mime, String fileName, int req) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, fileName);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "No file manager available to save the file.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_PDF) {
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
        } else if (requestCode == REQ_SAVE_BACKUP) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("no stream");
                int n = stock.writeBackup(out, db);
                Toast.makeText(this, "Backup saved (" + n + " spool(s)).", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not save the backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_LOAD_BACKUP) {
            final StockStore.Backup b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open the file.");
                b = StockStore.readBackup(in);
            } catch (Exception e) {
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Could not load backup")
                        .setMessage(e.getMessage())
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Load this backup?")
                    .setMessage("It contains " + b.spools + " spool(s) in " + b.entries + " entr" + (b.entries == 1 ? "y" : "ies")
                            + ". Your current stock will be replaced by the backup.")
                    .setPositiveButton("Load", (dlg, w) -> {
                        stock.apply(b);
                        Toast.makeText(this, "Backup loaded.", Toast.LENGTH_SHORT).show();
                        refreshStockPageKeepingScroll();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    // ---------------- UI helpers ----------------

    /** Adds A2L / H2C chips. @return true if at least one chip was added */
    private boolean addCompatTags(LinearLayout parent, String type) {
        boolean any = false;
        Compat.A2L a = Compat.a2l(type);
        if (a != Compat.A2L.NO) {
            parent.addView(chip(a == Compat.A2L.HARDENED ? "A2L · HS" : "A2L", a == Compat.A2L.HARDENED ? WARN : ACCENT));
            any = true;
        }
        if (Compat.h2c(type)) {
            parent.addView(chip("H2C", BLUE));
            any = true;
        }
        return any;
    }

    private TextView chip(String label, int color) {
        TextView t = text(label, 11, color, true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setStroke(dp(1), color);
        t.setBackground(g);
        t.setPadding(dp(7), dp(1), dp(7), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView stepButton(String label, int sizeDp) {
        TextView b = text(label, sizeDp >= 40 ? 22 : 18, FG, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.rgb(52, 52, 58));
        b.setBackground(g);
        b.setClickable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return b;
    }

    private LinearLayout.LayoutParams half() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams halfLeft() {
        LinearLayout.LayoutParams lp = half();
        lp.setMargins(0, 0, dp(8), 0);
        return lp;
    }

    private void highlightTabs() {
        styleTab(tabScan, onScan());
        styleTab(tabStock, onStockPage);
        styleTab(tabProducts, onProductsPage);
    }

    private boolean onScan() {
        return !onStockPage && !onProductsPage;
    }

    private Button tab(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
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

    private void askWebToken(final TextView status) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("m3d_…");
        in.setText(web.token());
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Website token")
                .setMessage("Paste the sync token for min3dstudio.com. Stock changes are then sent to the website automatically.")
                .setView(in)
                .setPositiveButton("Save & sync", (dlg, w) -> {
                    web.setToken(in.getText().toString());
                    if (!web.hasToken()) {
                        status.setText("Website sync off (no token).");
                        return;
                    }
                    status.setText("Syncing to min3dstudio.com…");
                    web.syncNow(stock, db, (ok, msg) -> status.setText(web.lastStatus()));
                })
                .setNegativeButton("Cancel", null)
                .show();
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
    static int argb(String hex) {
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
