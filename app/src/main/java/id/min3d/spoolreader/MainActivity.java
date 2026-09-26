package id.min3d.spoolreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Read-only Bambu Lab spool RFID reader. No INTERNET permission, nothing is written to the tag.
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private NfcAdapter nfc;
    private ColorDb db;
    private LinearLayout root;
    private TextView status;
    private boolean nfcWasOff;

    private static final int BG = Color.rgb(24, 24, 27);
    private static final int CARD = Color.rgb(39, 39, 42);
    private static final int FG = Color.rgb(244, 244, 245);
    private static final int MUTED = Color.rgb(161, 161, 170);
    private static final int ACCENT = Color.rgb(34, 197, 94);
    private static final int WARN = Color.rgb(250, 204, 21);
    private static final int ERR = Color.rgb(248, 113, 113);

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
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);
        nfc = NfcAdapter.getDefaultAdapter(this);
        showIdle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfc == null) {
            showMessage("HP ini tidak punya NFC.", ERR, false);
            return;
        }
        if (!nfc.isEnabled()) {
            showMessage("NFC sedang mati. Nyalakan NFC lalu kembali ke aplikasi ini.", WARN, true);
            nfcWasOff = true;
            return;
        }
        if (nfcWasOff) {
            nfcWasOff = false;
            showIdle();
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

    /** Called on a binder thread. */
    @Override
    public void onTagDiscovered(Tag tag) {
        runOnUiThread(() -> setStatus("Membaca tag… tahan HP tetap di spool", MUTED));
        final Result r = readTag(tag);
        runOnUiThread(() -> render(r));
    }

    private static final class Result {
        SpoolData data;
        String error;
    }

    private Result readTag(Tag tag) {
        Result res = new Result();
        if (!Arrays.asList(tag.getTechList()).contains(MifareClassic.class.getName())) {
            res.error = "Tag ini bukan MIFARE Classic, jadi bukan tag spool Bambu Lab.";
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
                        res.error = "Kunci tidak cocok. Kemungkinan ini bukan tag asli Bambu Lab (atau tag pihak ketiga).";
                        return res;
                    }
                }
                if (authed) blocks[b] = mc.readBlock(b);
            }
            res.data = SpoolData.parse(uid, blocks);
        } catch (IOException e) {
            res.error = "Pembacaan terputus. Tempelkan HP lagi dan tahan 1–2 detik tanpa digeser.";
        } catch (RuntimeException e) {
            res.error = "Gagal membaca tag: " + e.getClass().getSimpleName();
        } finally {
            try {
                mc.close();
            } catch (IOException ignored) {
            }
        }
        return res;
    }

    // ---------------- UI ----------------

    private void showIdle() {
        root.removeAllViews();
        addTitle();
        status = text("Tempelkan bagian belakang HP ke sisi spool (dekat label / tengah spool).", 16, MUTED, false);
        root.addView(status);
        if (db != null) {
            TextView info = text(db.size() + " warna di database (sumber: Bambu Studio resmi + data komunitas). "
                    + "Aplikasi ini hanya membaca, tidak pernah menulis ke tag, dan tidak memakai internet.", 12, MUTED, false);
            info.setPadding(0, dp(24), 0, 0);
            root.addView(info);
        }
    }

    private void showMessage(String msg, int color, boolean nfcButton) {
        root.removeAllViews();
        addTitle();
        status = text(msg, 16, color, false);
        root.addView(status);
        if (nfcButton) {
            Button b = new Button(this);
            b.setText("Buka pengaturan NFC");
            b.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));
            root.addView(b);
        }
    }

    private void setStatus(String s, int color) {
        if (status != null) {
            status.setText(s);
            status.setTextColor(color);
        }
    }

    private void render(Result r) {
        if (r.error != null) {
            showMessage(r.error, ERR, false);
            return;
        }
        SpoolData d = r.data;
        ColorDb.Match m = db == null ? null : db.lookup(d);

        root.removeAllViews();
        addTitle();

        // swatches
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        for (String c : d.colors) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(argb(c));
            g.setCornerRadius(dp(14));
            g.setStroke(dp(1), Color.rgb(82, 82, 91));
            v.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(90), 1f);
            lp.setMargins(0, 0, dp(8), 0);
            sw.addView(v, lp);
        }
        root.addView(sw);

        String name = m != null ? m.name : "Nama warna tidak dikenal";
        TextView big = text(name, 30, m != null ? FG : WARN, true);
        big.setPadding(0, dp(16), 0, dp(2));
        root.addView(big);

        String type = !d.detailedType.isEmpty() ? d.detailedType : d.filamentType;
        root.addView(text(type, 18, MUTED, false));

        String conf;
        int confColor;
        if (m == null) {
            conf = "Tidak ada di database. Cocokkan kode varian & hex di bawah dengan katalog Bambu.";
            confColor = WARN;
        } else if ("kode".equals(m.how)) {
            conf = "✓ Cocok dengan kode varian di tabel resmi Bambu Studio";
            confColor = ACCENT;
        } else if ("hex".equals(m.how)) {
            conf = "✓ Cocok dengan kode warna (hex) di tabel resmi Bambu Studio";
            confColor = ACCENT;
        } else if ("hex1".equals(m.how)) {
            conf = "≈ Hanya warna pertama yang cocok di tabel resmi — cek ulang";
            confColor = WARN;
        } else {
            conf = "≈ Dari data komunitas (bukan tabel resmi) — cek ulang";
            confColor = WARN;
        }
        TextView c = text(conf, 13, confColor, false);
        c.setPadding(0, dp(8), 0, dp(16));
        root.addView(c);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(CARD);
        cg.setCornerRadius(dp(12));
        card.setBackground(cg);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        row(card, "Kode varian", d.variantId);
        row(card, "Material ID", d.materialId);
        row(card, "Warna (hex)", join(d.colors));
        if (d.weightG > 0) row(card, "Berat filamen", d.weightG + " g");
        if (d.lengthM > 0) row(card, "Panjang", "± " + d.lengthM + " m");
        if (d.diameterMm > 0) row(card, "Diameter", String.format(Locale.ROOT, "%.2f mm", d.diameterMm));
        if (d.hotendMinC > 0) row(card, "Suhu nozzle", d.hotendMinC + "–" + d.hotendMaxC + " °C");
        if (d.bedTempC > 0) row(card, "Suhu bed", d.bedTempC + " °C");
        if (d.dryTempC > 0) row(card, "Pengeringan", d.dryTempC + " °C, " + d.dryHours + " jam");
        if (!d.productionDate.isEmpty()) row(card, "Diproduksi", d.productionDate.replace('_', ' '));
        row(card, "UID tag", d.uid);
        root.addView(card);

        status = text("Tempelkan ke spool lain untuk membaca lagi.", 13, MUTED, false);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);
    }

    private void addTitle() {
        TextView t = text("Bambu Spool Reader", 14, MUTED, true);
        t.setPadding(0, 0, 0, dp(16));
        root.addView(t);
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

    private static String join(java.util.List<String> l) {
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
