package id.min3d.spoolreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Sends the in-stock filament list to the min3dstudio.com database (Supabase), so the website
 * only offers colours that are actually in stock. Only colour names, hex codes and spool counts
 * are sent. The upload needs a sync token (entered once in the app); without it nothing is sent.
 */
public final class WebSync {

    static final String URL_RPC = "https://kjsigfesocabfdckehwo.supabase.co/rest/v1/rpc/sync_filament_stock";
    static final String API_KEY = "sb_publishable_UHJzxe3h3Vyuu23nKAKDqw_gfsB_-3U"; // public key, safe to ship

    public interface Callback {
        void done(boolean ok, String message);
    }

    private static final String PREF = "web_sync";
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pending;

    public WebSync(Context ctx) {
        prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String token() {
        return prefs.getString("token", "");
    }

    public void setToken(String t) {
        prefs.edit().putString("token", t == null ? "" : t.trim()).apply();
    }

    public boolean hasToken() {
        return !token().isEmpty();
    }

    public String lastStatus() {
        return prefs.getString("last", "");
    }

    /** Debounced automatic sync after stock edits (3 s after the last change). */
    public void schedule(final StockStore stock, final ColorDb db) {
        if (!hasToken() || db == null) return;
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> {
            pending = null;
            syncNow(stock, db, null);
        };
        main.postDelayed(pending, 3000);
    }

    public void syncNow(StockStore stock, ColorDb db, final Callback cb) {
        final String token = token();
        if (token.isEmpty()) {
            if (cb != null) cb.done(false, "No website token set.");
            return;
        }
        final String body = payload(token, stock.grouped(db));
        new Thread(() -> {
            boolean ok = false;
            String msg;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(URL_RPC).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setDoOutput(true);
                c.setRequestProperty("apikey", API_KEY);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(b);
                }
                int code = c.getResponseCode();
                String resp = read(code < 400 ? c.getInputStream() : c.getErrorStream());
                ok = code >= 200 && code < 300;
                if (ok) msg = "Website updated: " + resp;
                else if (resp.contains("token")) msg = "Website token is wrong.";
                else msg = "Website sync failed (HTTP " + code + ").";
            } catch (Exception e) {
                msg = "No internet connection – website not updated.";
            } finally {
                if (c != null) c.disconnect();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            String stamp = new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US).format(new java.util.Date());
            prefs.edit().putString("last", (fOk ? "✓ " : "✗ ") + stamp + " – " + fMsg).apply();
            if (cb != null) main.post(() -> cb.done(fOk, fMsg));
        }).start();
    }

    static String payload(String token, Map<String, List<StockStore.Item>> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"p_token\":").append(q(token)).append(",\"p_items\":[");
        boolean first = true;
        for (List<StockStore.Item> l : groups.values()) {
            for (StockStore.Item it : l) {
                if (it.qty <= 0) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"key\":").append(q(it.key))
                        .append(",\"type\":").append(q(it.type))
                        .append(",\"name\":").append(q(it.name))
                        .append(",\"code\":").append(q(it.code))
                        .append(",\"qty\":").append(it.qty)
                        .append(",\"colors\":[");
                for (int i = 0; i < it.colors.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append(q(it.colors.get(i)));
                }
                sb.append("]}");
            }
        }
        return sb.append("]}").toString();
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }
}
