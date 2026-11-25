/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Line and settings of this build: assets/brand.json.
 * <pre>
 * {"v":1, "brand":"cloud"|"vice", "name":"Cloud Talk", "product":"talk", "theme":"",
 *  "api_id":0, "api_hash":"",                       — written at signing time, never in the public repository
 *  "bypass":{"mode":"auto", "profiles_url":"", "xray":[ outbound… ], "mtproto":[{"server","port","secret"}…]},
 *  "downloads":"https://cabinet.zjd.uk/app/downloads.json", "ads_slot":"talk.settings"}
 * </pre>
 * The Vice APK is the Cloud APK with another brand.json and launcher alias (tools/brand_apk.py), same key.
 */
public final class Brand {
    public static String line = "cloud";
    public static String name = "Cloud Talk";
    public static String product = "talk";
    public static String theme = "";
    public static JSONObject json = new JSONObject();
    private static boolean loaded;

    private Brand() {
    }

    public static synchronized void init(Context ctx) {
        if (loaded || ctx == null) {
            return;
        }
        loaded = true;
        try (InputStream in = ctx.getAssets().open("brand.json")) {
            json = new JSONObject(readAll(in));
        } catch (Throwable ignore) {
            json = new JSONObject();
        }
        line = "vice".equals(json.optString("brand")) ? "vice" : "cloud";
        name = json.optString("name", isVice() ? "Vice Talk" : "Cloud Talk");
        product = json.optString("product", isVice() ? "vice-talk" : "talk");
        theme = json.optString("theme", "");
        final int id = json.optInt("api_id", 0);
        final String hash = json.optString("api_hash", "");
        if (id > 0 && hash.length() == 32) {
            BuildVars.APP_ID = id;
            BuildVars.APP_HASH = hash;
        }
        defaultTheme(ctx);
    }

    /** The theme of the line until the person chooses one; after switching the line the other line's theme applies. */
    private static void defaultTheme(Context ctx) {
        final SharedPreferences main = ctx.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        final SharedPreferences own = ctx.getSharedPreferences("tgx_brand", Context.MODE_PRIVATE);
        final String before = own.getString("line", null);
        if (line.equals(before)) {
            return;
        }
        own.edit().putString("line", line).apply();
        Account.lineChanged(ctx, before == null);
        final boolean chosen = main.contains("theme") && before == null || own.getBoolean("theme_chosen", false);
        if (chosen) {
            return;
        }
        final SharedPreferences cfg = ctx.getSharedPreferences("themeconfig", Context.MODE_PRIVATE);
        if (isVice()) {
            // «follow the system» (Telegram's default since Android 10) replaces a dark day theme by the stock light one
            main.edit().putString("theme", "Vice Night").putString("nighttheme", "Vice Night").putInt("selectedAutoNightType", 0).apply();
            cfg.edit().putString("lastDayTheme", "Vice Day").putString("lastDarkTheme", "Vice Night").apply();   // the day/night switch
        } else if (before != null) {
            main.edit().putString("theme", "Blue").putString("nighttheme", "Dark Blue").remove("selectedAutoNightType").apply();
            cfg.edit().putString("lastDayTheme", "Blue").putString("lastDarkTheme", "Dark Blue").apply();
        }
    }

    /** First screen: our icon (inside Telegram's circle mask, nothing of Telegram's logo). */
    public static Bitmap introIcon(int size) {
        final Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            final Paint page = new Paint();
            page.setColor(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhite));
            new Canvas(out).drawRect(0, 0, size, size, page);   // no ring through the transparent corners of the icon
            final int res = isVice() ? R.drawable.tgx_logo_vice : R.drawable.tgx_logo_cloud;
            final Bitmap src = BitmapFactory.decodeResource(org.telegram.messenger.ApplicationLoader.applicationContext.getResources(), res);
            if (src != null) {
                final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                final float m = size * .14f;   // inside the circle: the corners of the square icon stay visible
                new Canvas(out).drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()), new RectF(m, m, size - m, size - m), p);
                src.recycle();
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    public static boolean isVice() {
        return "vice".equals(line);
    }

    /** api_id is set: without it the client must not talk to Telegram (no fallback to official keys). */
    public static boolean hasApiKeys() {
        return BuildVars.APP_ID > 0 && !TextUtils.isEmpty(BuildVars.APP_HASH);
    }

    public static JSONObject bypass() {
        final JSONObject b = json.optJSONObject("bypass");
        return b != null ? b : new JSONObject();
    }

    public static JSONArray array(JSONObject o, String key) {
        final JSONArray a = o.optJSONArray(key);
        return a != null ? a : new JSONArray();
    }

    public static boolean ru() {
        final String l = Locale.getDefault().getLanguage();
        return "ru".equals(l) || "uk".equals(l) || "be".equals(l) || "kk".equals(l);
    }

    public static String t(String ru, String en) {
        return ru() ? ru : en;
    }

    /** Strings that would call the app «Telegram». Everything else (the service name) stays as is. */
    public static String override(String key, int res) {
        if (res == R.string.Page1Message || "Page1Message".equals(key)) {
            // Telegram API terms 2.2: the intro says that this is an unofficial client on the Telegram API
            return t("**Неофициальный** клиент Telegram от CloudVPN.\nВстроенный **обход блокировок**.",
                    "**Unofficial** Telegram client by CloudVPN.\nWith a built-in **bypass of blocks**.");
        }
        if (res != 0) {
            if (res == R.string.AppName || res == R.string.AppNameBeta || res == R.string.Page1Title) {
                return name;
            }
        } else if (key != null) {
            if ("AppName".equals(key) || "AppNameBeta".equals(key) || "Page1Title".equals(key)) {
                return name;
            }
        }
        return null;
    }

    static String readAll(InputStream in) throws java.io.IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
