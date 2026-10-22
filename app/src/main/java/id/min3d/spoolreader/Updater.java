package id.min3d.spoolreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * In-app update from the GitHub Releases of this repository (no third-party libraries).
 * <ol>
 *   <li>Checks the latest release (at most every 6 h when the app is opened, or on request).</li>
 *   <li>Downloads the APK into the app's private cache and verifies its SHA-256 against SHA256.txt of the release.</li>
 *   <li>Hands it to Android's PackageInstaller; Android shows its own "Install" confirmation (it never installs
 *       silently) and only accepts the update when it is signed with the same key as the installed app.</li>
 * </ol>
 */
public final class Updater {

    static final String LATEST = "https://api.github.com/repos/aidarinol/bambu-spool-reader/releases/latest";
    private static final String ACTION_STATUS = "id.min3d.spoolreader.UPDATE_INSTALL_STATUS";
    private static final long CHECK_EVERY_MS = 6L * 3600 * 1000;

    public static final class Release {
        public final int build;
        public final String tag, apkUrl, apkName, shaUrl, notes;
        public final long size;

        Release(int build, String tag, String apkUrl, String apkName, long size, String shaUrl, String notes) {
            this.build = build;
            this.tag = tag;
            this.apkUrl = apkUrl;
            this.apkName = apkName;
            this.size = size;
            this.shaUrl = shaUrl;
            this.notes = notes;
        }
    }

    private final Activity act;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy, dialogShown;

    public Updater(Activity act) {
        this.act = act;
        this.prefs = act.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    /** Build number of the installed app (= GitHub Actions run number, same as the release tag v1.1.N). */
    public int currentBuild() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public String currentName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Called when the app comes to the foreground: check quietly if the last check is old enough. */
    public void checkIfDue() {
        long last = prefs.getLong("last_check", 0);
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return;
        check(false, null);
    }

    /**
     * @param manual true when the user asked: also report "up to date" / errors.
     * @param status optional line to show the result in
     */
    public void check(final boolean manual, final TextView status) {
        if (busy) return;
        busy = true;
        if (status != null) status.setText("Checking for updates…");
        new Thread(() -> {
            Release r = null;
            String err = null;
            try {
                r = fetchLatest();
            } catch (Exception e) {
                err = "Could not check for updates (no internet?).";
            }
            final Release fr = r;
            final String fErr = err;
            main.post(() -> {
                busy = false;
                if (act.isFinishing()) return;
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
                if (fErr != null) {
                    if (status != null) status.setText(versionLine());
                    if (manual) Toast.makeText(act, fErr, Toast.LENGTH_LONG).show();
                    return;
                }
                if (fr != null && fr.build > currentBuild() && fr.apkUrl != null) {
                    if (status != null) status.setText("Update available: v1.1." + fr.build + " – tap to install");
                    if (manual || (!dialogShown && prefs.getInt("skipped", 0) != fr.build)) offer(fr);
                } else {
                    if (status != null) status.setText(versionLine() + " · up to date");
                    if (manual) Toast.makeText(act, "Min3D Studio is up to date (v" + currentName() + ").", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    public String versionLine() {
        return "App version v" + currentName() + " – tap to check for updates";
    }

    private void offer(final Release r) {
        dialogShown = true;
        String size = r.size > 0 ? String.format(Locale.US, " (%.1f MB)", r.size / 1048576.0) : "";
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Update available")
                .setMessage("Min3D Studio v1.1." + r.build + " is available" + size + ". You have v" + currentName()
                        + ".\n\nYour stock stays on the phone and in the website database.\n\nAndroid will ask you to confirm the install.")
                .setPositiveButton("Update", (d, w) -> download(r))
                .setNegativeButton("Later", (d, w) -> prefs.edit().putInt("skipped", r.build).apply())
                .show();
    }

    // ---------------- download + verify ----------------

    private void download(final Release r) {
        final TextView msg = new TextView(act);
        int p = (int) (20 * act.getResources().getDisplayMetrics().density);
        msg.setPadding(p, p, p, p);
        msg.setText("Downloading v1.1." + r.build + "…");
        final AlertDialog dlg = new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Updating Min3D Studio").setView(msg).setCancelable(false).show();
        new Thread(() -> {
            String err = null;
            File apk = new File(act.getCacheDir(), "update.apk");
            try {
                String expected = r.shaUrl == null ? null : expectedSha(readText(r.shaUrl), r.apkName);
                if (expected == null) throw new Exception("Release has no SHA256 checksum – not installing.");
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                HttpURLConnection c = open(r.apkUrl);
                long total = c.getContentLength() > 0 ? c.getContentLength() : r.size, done = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long lastUi = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (total > 0 && now - lastUi > 250) {
                            lastUi = now;
                            final int pct = (int) (done * 100 / total);
                            main.post(() -> msg.setText("Downloading v1.1." + r.build + "… " + pct + "%"));
                        }
                    }
                } finally {
                    c.disconnect();
                }
                String got = hex(md.digest());
                if (!got.equalsIgnoreCase(expected)) {
                    apk.delete();
                    throw new Exception("Downloaded file is damaged (checksum mismatch) – not installing. Try again.");
                }
            } catch (Exception e) {
                err = e.getMessage() != null && e.getMessage().contains("checksum") ? e.getMessage() : "Download failed. Check the internet connection and try again.";
            }
            final String fErr = err;
            main.post(() -> {
                dlg.dismiss();
                if (fErr != null) {
                    new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Update failed").setMessage(fErr).setPositiveButton("OK", null).show();
                    return;
                }
                install(apk);
            });
        }).start();
    }

    // ---------------- install via PackageInstaller ----------------

    private File pendingApk;

    /** Call from the Activity's onResume: continues after the user allowed "Install unknown apps". */
    public void onResume() {
        if (pendingApk != null && canInstall()) {
            File f = pendingApk;
            pendingApk = null;
            install(f);
        }
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || act.getPackageManager().canRequestPackageInstalls();
    }

    private void install(final File apk) {
        if (!canInstall()) {
            pendingApk = apk;
            new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Allow updates")
                    .setMessage("One-time step: allow Min3D Studio to install its own updates.\n\nOn the next screen switch on \"Allow from this source\", then come back.")
                    .setPositiveButton("Open settings", (d, w) -> act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + act.getPackageName()))))
                    .setNegativeButton("Cancel", (d, w) -> pendingApk = null)
                    .show();
            return;
        }
        try {
            PackageInstaller pi = act.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(act.getPackageName());
            int id = pi.createSession(params);
            PackageInstaller.Session s = pi.openSession(id);
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("min3d.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            registerStatusReceiver();
            Intent i = new Intent(ACTION_STATUS).setPackage(act.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent piIntent = PendingIntent.getBroadcast(act, id, i, flags);
            s.commit(piIntent.getIntentSender());
            s.close();
        } catch (Exception e) {
            new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Update failed").setMessage("Could not start the installer: " + e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private BroadcastReceiver receiver;

    private void registerStatusReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(confirm); // Android's own "Install" screen
                    }
                } else if (st != PackageInstaller.STATUS_SUCCESS) {
                    String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    if (st == PackageInstaller.STATUS_FAILURE_ABORTED) m = "Install cancelled.";
                    else if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                        m = "Android refused the update (" + m + "). The update must be signed with the same key as this app.";
                    Toast.makeText(act, "Update not installed: " + m, Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else act.registerReceiver(receiver, f);
    }

    /** Call from the Activity's onDestroy. */
    public void release() {
        if (receiver != null) {
            try {
                act.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
    }

    // ---------------- GitHub ----------------

    static Release fetchLatest() throws Exception {
        JSONObject o = new JSONObject(readText(LATEST));
        String tag = o.optString("tag_name", "");
        int build = parseBuild(tag);
        String apkUrl = null, apkName = null, shaUrl = null;
        long size = 0;
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                apkUrl = url;
                apkName = name;
                size = a.optLong("size", 0);
            } else if (name.equalsIgnoreCase("SHA256.txt")) shaUrl = url;
        }
        return new Release(build, tag, apkUrl, apkName, size, shaUrl, o.optString("body", ""));
    }

    /** "v1.1.12" -> 12 */
    static int parseBuild(String tag) {
        String t = tag == null ? "" : tag.trim();
        int dot = t.lastIndexOf('.');
        try {
            return Integer.parseInt(dot >= 0 ? t.substring(dot + 1) : t.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** SHA256.txt lines look like "&lt;hex&gt;  out/Min3D-Studio-v1.1.12.apk". */
    static String expectedSha(String text, String apkName) {
        if (text == null) return null;
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            String[] f = l.split("\\s+", 2);
            if (f.length == 2 && f[0].matches("[0-9a-fA-F]{64}") && (apkName == null || f[1].endsWith(apkName))) return f[0];
        }
        return null;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Min3D-Studio-app");
        c.setRequestProperty("Accept", url.startsWith("https://api.github.com") ? "application/vnd.github+json" : "*/*");
        int code = c.getResponseCode();
        if (code >= 300 && code < 400) { // follow cross-host redirect (github.com -> objects.githubusercontent.com)
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) throw new Exception("redirect without location");
            return open(loc);
        }
        if (code != 200) throw new Exception("HTTP " + code);
        return c;
    }

    private static String readText(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
