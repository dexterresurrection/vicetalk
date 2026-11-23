/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;

/**
 * In-app updates from our downloads.json (same file as the bot «📲 Приложения» and the cabinet), product = brand.json
 * «product». The APK is downloaded inside the app (RU mirror → our server → GitHub), its sha256 checked against
 * downloads.json, then the system installer is opened (same key, so it is an update that keeps the data).
 */
public final class Updater {
    private static final String DEFAULT = "https://cabinet.zjd.uk/app/downloads.json";
    private static boolean checking;

    private Updater() {
    }

    public static void check(Activity activity, boolean force) {
        if (activity == null || checking) {
            return;
        }
        final SharedPreferences p = activity.getSharedPreferences("tgx_update", Context.MODE_PRIVATE);
        if (!force && System.currentTimeMillis() - p.getLong("at", 0) < 6 * 3600_000L) {
            return;
        }
        checking = true;
        new Thread(() -> {
            try {
                final String url = Brand.json.optString("downloads", DEFAULT);
                final JSONObject d = new JSONObject(get(url));
                final JSONObject prod = d.getJSONObject("products").optJSONObject(Brand.product);
                p.edit().putLong("at", System.currentTimeMillis()).apply();
                if (prod == null) {
                    return;
                }
                final String latest = prod.optString("version");
                final String mine = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
                Bypass.note("update: mine " + ourVersion(mine) + ", latest " + latest);
                if (!newer(latest, ourVersion(mine)) || (!force && latest.equals(p.getString("skip", "")))) {
                    return;
                }
                final JSONObject file = pickFile(prod.optJSONArray("files"));
                if (file == null) {
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    new AlertDialog.Builder(activity)
                            .setTitle(Brand.t("Доступно обновление", "Update available"))
                            .setMessage(Brand.name + " " + latest + (prod.optString("notes").isEmpty() ? "" : "\n\n" + prod.optString("notes")))
                            .setPositiveButton(Brand.t("Обновить", "Update"), (dlg, w) -> download(activity, file, latest))
                            .setNegativeButton(Brand.t("Позже", "Later"), null)
                            .setNeutralButton(Brand.t("Пропустить", "Skip"), (dlg, w) -> p.edit().putString("skip", latest).apply())
                            .show();
                });
            } catch (Throwable e) {
                Bypass.note("update check: " + e.getClass().getSimpleName());
            } finally {
                checking = false;
            }
        }, "tgx-update").start();
    }

    private static void download(Activity activity, JSONObject file, String version) {
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.show();
        new Thread(() -> {
            final File dir = new File(activity.getFilesDir(), "cache");
            dir.mkdirs();
            final File apk = new File(dir, "tgx-update-" + version.replaceAll("[^0-9A-Za-z.]", "") + ".apk");
            final File[] old = dir.listFiles((d, n) -> n.startsWith("tgx-update") && !n.startsWith(apk.getName()));
            if (old != null) {
                for (File f : old) {   // parts and files of other versions
                    f.delete();
                }
            }
            String error = null;
            final ArrayList<String> urls = new ArrayList<>();
            for (String k : new String[]{"bucket", "server", "github"}) {   // the RU mirror first: GitHub is slow from Russia
                if (!file.optString(k).isEmpty()) {
                    urls.add(file.optString(k));
                }
            }
            boolean ok = false;
            for (String u : urls) {
                try {
                    fetch(u, apk);
                    final String want = file.optString("sha256");
                    if (!want.isEmpty() && !want.equalsIgnoreCase(sha256(apk))) {
                        error = "sha256";
                        apk.delete();
                        continue;
                    }
                    ok = true;
                    Bypass.note("update: downloaded " + version + " from " + Uri.parse(u).getHost());
                    break;
                } catch (Throwable e) {
                    error = e.getClass().getSimpleName();
                }
            }
            final boolean done = ok;
            final String err = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progress.dismiss();
                } catch (Throwable ignore) {
                }
                if (!done) {
                    new AlertDialog.Builder(activity).setTitle(Brand.name)
                            .setMessage(Brand.t("Не удалось скачать обновление: ", "Could not download the update: ") + err)
                            .setPositiveButton("OK", null).show();
                    return;
                }
                install(activity, apk);
            });
        }, "tgx-update-dl").start();
    }

    private static void install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            // one-time permission to install updates from this app; the installer opens by itself once it is given
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable ignore) {
            }
            Bypass.note("update: asked for the install permission");
            new Thread(() -> {
                for (int i = 0; i < 180; i++) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (activity.getPackageManager().canRequestPackageInstalls()) {
                        AndroidUtilities.runOnUIThread(() -> install(activity, apk));
                        return;
                    }
                }
            }, "tgx-update-perm").start();
            return;
        }
        try {
            final Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk);
            final Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            Bypass.note("update: installer opened");
        } catch (Throwable e) {
            Bypass.note("update: installer " + e.getClass().getSimpleName());
        }
    }

    /** versionName is «13.2.2-ct.0.1.0» (Telegram base + ours): only our part is compared. */
    static String ourVersion(String versionName) {
        final int i = versionName.lastIndexOf("-ct.");
        return i >= 0 ? versionName.substring(i + 4) : versionName;
    }

    static boolean newer(String a, String b) {
        final String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return p > q;
            }
        }
        return false;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static JSONObject pickFile(JSONArray files) {
        if (files == null) {
            return null;
        }
        JSONObject universal = null;
        final boolean arm64 = Build.SUPPORTED_ABIS.length > 0 && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]);
        for (int i = 0; i < files.length(); i++) {
            final JSONObject f = files.optJSONObject(i);
            if (f == null || !f.optString("name").endsWith(".apk")) {
                continue;
            }
            final String flavor = f.optString("flavor");
            if (arm64 && flavor.endsWith("-arm64")) {
                return f;
            }
            if (flavor.endsWith("-universal") || universal == null) {
                universal = f;
            }
        }
        return universal;
    }

    /** Downloads with resume: the part stays in «…part», the next attempt (or the next mirror of the same file) continues it. */
    private static void fetch(String url, File to) throws Exception {
        final File part = new File(to.getPath() + ".part");
        for (int attempt = 0; ; attempt++) {
            final long have = part.length();
            final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (have > 0) {
                c.setRequestProperty("Range", "bytes=" + have + "-");
            }
            try {
                final int code = c.getResponseCode();
                if (code == 416 && have > 0) {
                    break;   // the part is complete
                }
                if (code != 200 && code != 206) {
                    throw new java.io.IOException("HTTP " + code);
                }
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, code == 206)) {
                    final byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                break;
            } catch (java.io.IOException e) {
                if (attempt >= 4) {
                    throw e;
                }
                Thread.sleep(2000L * (attempt + 1));
            } finally {
                c.disconnect();
            }
        }
        to.delete();
        if (!part.renameTo(to)) {
            throw new java.io.IOException("rename");
        }
    }

    private static String sha256(File f) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            final byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }

    private static String get(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try {
            return Brand.readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
