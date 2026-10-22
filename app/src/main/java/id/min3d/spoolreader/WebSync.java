package id.min3d.spoolreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Two-way stock sync with the min3dstudio.com database (Supabase). The database is the shared stock list:
 * the owner can change counts on the website too.
 * Fully automatic while the app is open: changes on this phone are uploaded 1.5 s after the last tap, and every
 * {@link #POLL_MS} ms the app asks the database only for rows changed since the last check (usually none).
 * When the app is closed nothing runs; the stock is brought up to date as soon as the app is opened again.
 * <ul>
 *   <li>First run of this version: the whole stock on this phone is uploaded once (the phone was the only
 *       source until now). After that the old one-way sync is refused by the server.</li>
 *   <li>After that: only counts changed on this phone are uploaded, then the latest list is downloaded.
 *       Counts changed on this phone but not uploaded yet always win over the download.</li>
 * </ul>
 * Needs the sync token (entered once in the app); without it nothing is sent or received.
 */
public final class WebSync {

    static final String BASE = "https://kjsigfesocabfdckehwo.supabase.co/rest/v1/rpc/";
    static final String API_KEY = "sb_publishable_UHJzxe3h3Vyuu23nKAKDqw_gfsB_-3U"; // public key, safe to ship

    public interface Callback {
        void done(boolean ok, String message);
    }

    private static final String PREF = "web_sync";
    private static final String FULL_DONE = "two_way_full_done";
    private static final String SINCE = "pull_since";
    static final long POLL_MS = 8000;
    static final long PUSH_DELAY_MS = 1500;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private volatile boolean running;
    private long lastPull;

    /** Runs on the main thread after the download changed counts on this phone (refresh the screen). */
    public Runnable onRemoteChanged;
    /** Runs on the main thread after every sync attempt (update the status line). */
    public Runnable onStatus;

    private StockStore autoStock;
    private ColorDb autoDb;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (autoStock == null) return;
            if (!running && pending == null && hasToken() && autoDb != null) {
                if (!prefs.getBoolean(FULL_DONE, false) || !autoStock.dirty().isEmpty()) syncNow(autoStock, autoDb, null);
                else pullChanges(autoStock, autoDb);
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    /** Start automatic two-way sync (call from onResume). Syncs right away, then checks every few seconds. */
    public void startAuto(StockStore stock, ColorDb db) {
        autoStock = stock;
        autoDb = db;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    /** Stop the automatic checks (call from onPause); a pending upload still goes out. */
    public void stopAuto() {
        main.removeCallbacks(tick);
        autoStock = null;
    }

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

    /** True while a stock change has not reached the website yet (offline, error, app closed early). */
    public boolean pending() {
        return prefs.getBoolean("pending", false);
    }

    /** Debounced automatic upload after stock edits (1.5 s after the last change). */
    public void schedule(final StockStore stock, final ColorDb db) {
        if (!hasToken() || db == null) return;
        prefs.edit().putBoolean("pending", true).apply();
        if (pending != null) main.removeCallbacks(pending);
        pending = () -> {
            pending = null;
            syncNow(stock, db, null);
        };
        main.postDelayed(pending, PUSH_DELAY_MS);
    }

    public void syncNow(final StockStore stock, final ColorDb db, final Callback cb) {
        final String token = token();
        if (token.isEmpty()) {
            if (cb != null) cb.done(false, "No website token set.");
            return;
        }
        if (db == null) {
            if (cb != null) cb.done(false, "Colour database missing.");
            return;
        }
        if (running) { // one sync at a time; try again shortly
            main.postDelayed(() -> syncNow(stock, db, cb), 1500);
            return;
        }
        running = true;
        final boolean full = !prefs.getBoolean(FULL_DONE, false);
        if (full) stock.markAllDirty(db);
        final Map<String, Integer> push = stock.dirty();
        final Map<String, StockStore.Item> info = stock.byKey(db);
        new Thread(() -> {
            boolean ok = false;
            String msg;
            int changed = 0;
            try {
                JSONObject resp;
                if (full || !push.isEmpty()) {
                    JSONObject body = new JSONObject();
                    body.put("p_token", token);
                    body.put("p_items", items(push, info));
                    body.put("p_full", full);
                    resp = post("app_push_stock", body);
                } else {
                    JSONObject body = new JSONObject();
                    body.put("p_token", token);
                    resp = post("app_pull_stock", body);
                }
                stock.confirmPushed(push);
                if (full) prefs.edit().putBoolean(FULL_DONE, true).remove(SINCE).apply();
                changed = stock.applyRemote(rows(resp.optJSONArray("rows")), db);
                lastPull = System.currentTimeMillis();
                ok = true;
                int up = push.size();
                msg = full ? "First full upload done – stock is now shared with min3dstudio.com."
                        : (up > 0 ? "Sent " + up + " change(s) to the website" : "Up to date")
                        + (changed > 0 ? ", received " + changed + " change(s) from the website" : "") + ".";
            } catch (SyncError e) {
                msg = e.getMessage();
            } catch (Exception e) {
                msg = "No internet connection – website not updated.";
            }
            running = false;
            final boolean fOk = ok;
            final String fMsg = msg;
            final int fChanged = changed;
            String stamp = new java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            prefs.edit().putString("last", (fOk ? "✓ " : "✗ ") + stamp + " – " + fMsg + (fOk ? "" : " (will keep trying automatically)"))
                    .putBoolean("pending", !fOk || !stock.dirty().isEmpty()).apply();
            main.post(() -> {
                if (fChanged > 0 && onRemoteChanged != null) onRemoteChanged.run();
                if (onStatus != null) onStatus.run();
                if (cb != null) cb.done(fOk, fMsg);
            });
        }).start();
    }

    /** Light check: only rows changed in the database since the last check (usually none). */
    private void pullChanges(final StockStore stock, final ColorDb db) {
        final String token = token();
        final String since = prefs.getString(SINCE, "");
        running = true;
        new Thread(() -> {
            boolean ok = false;
            int changed = 0;
            String msg;
            try {
                JSONObject body = new JSONObject();
                body.put("p_token", token);
                if (!since.isEmpty()) body.put("p_since", since);
                JSONObject resp = post("app_pull_changes", body);
                changed = stock.applyRemote(rows(resp.optJSONArray("rows")), db);
                String now = resp.optString("now", "");
                if (!now.isEmpty()) prefs.edit().putString(SINCE, now).apply();
                lastPull = System.currentTimeMillis();
                ok = true;
                msg = changed > 0 ? "Received " + changed + " change(s) from the website." : "Up to date.";
            } catch (SyncError e) {
                msg = e.getMessage();
            } catch (Exception e) {
                msg = "No internet connection – will keep trying.";
            }
            running = false;
            final boolean fOk = ok;
            final int fChanged = changed;
            String stamp = new java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            prefs.edit().putString("last", (fOk ? "✓ " : "✗ ") + stamp + " – " + msg).apply();
            main.post(() -> {
                if (fChanged > 0 && onRemoteChanged != null) onRemoteChanged.run();
                if (onStatus != null) onStatus.run();
            });
        }).start();
    }

    private static final class SyncError extends Exception {
        SyncError(String m) {
            super(m);
        }
    }

    static JSONArray items(Map<String, Integer> push, Map<String, StockStore.Item> info) throws Exception {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, Integer> e : push.entrySet()) {
            StockStore.Item it = info.get(e.getKey());
            JSONObject o = new JSONObject();
            o.put("key", e.getKey());
            o.put("qty", e.getValue());
            if (it != null) {
                o.put("type", it.type);
                o.put("name", it.name);
                o.put("code", it.code);
                JSONArray c = new JSONArray();
                for (String s : it.colors) c.put(s);
                o.put("colors", c);
            }
            a.put(o);
        }
        return a;
    }

    static List<StockStore.Remote> rows(JSONArray a) {
        List<StockStore.Remote> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            List<String> cols = new ArrayList<>();
            JSONArray c = o.optJSONArray("colors");
            if (c != null) for (int k = 0; k < c.length(); k++) cols.add(c.optString(k));
            out.add(new StockStore.Remote(o.optString("key"), o.optString("type", "Other"), o.optString("name", "?"),
                    o.optString("code", ""), cols, o.optInt("qty", 0)));
        }
        return out;
    }

    private static JSONObject post(String fn, JSONObject body) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + fn).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setDoOutput(true);
            c.setRequestProperty("apikey", API_KEY);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            int code = c.getResponseCode();
            String resp = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 200 && code < 300) return new JSONObject(resp);
            if (resp.contains("token")) throw new SyncError("Website token is wrong.");
            throw new SyncError("Website sync failed (HTTP " + code + ").");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }
}
