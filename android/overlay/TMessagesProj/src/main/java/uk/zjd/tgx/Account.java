/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * CloudVPN account: the bypass is part of the subscription (owner's decision). Sign-in by a device code confirmed in
 * the cabinet (or in the bot), like our other apps: POST /cabinet/auth/device/request → the person confirms the code →
 * POST /cabinet/auth/deeplink/poll returns the tokens. The bypass profiles come from GET /cabinet/app/talk-profile
 * (Xray outbounds of the person's own subscription, no extra device slot, plus our MTProto proxies).
 */
public final class Account {
    private static final String[] ORIGINS = {"https://cabinet.zjd.uk", "https://cabinet.0nn.us"};
    private static volatile boolean loginRunning;

    public interface LoginListener {
        void onCode(String userCode, String verificationUrl, String telegramUrl);

        void onDone(boolean ok, String error);
    }

    private Account() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tgx_account", Context.MODE_PRIVATE);
    }

    public static boolean loggedIn(Context c) {
        return !prefs(c).getString("refresh", "").isEmpty();
    }

    public static String userName(Context c) {
        return prefs(c).getString("name", "");
    }

    public static void logout(Context c) {
        prefs(c).edit().clear().apply();
        Bypass.clearProfiles();
    }

    public static void cancelLogin() {
        loginRunning = false;
    }

    /** Device-code sign-in; listener calls arrive on the UI thread. */
    public static void startLogin(Context ctx, LoginListener l) {
        final Context c = ctx.getApplicationContext();
        loginRunning = true;
        new Thread(() -> {
            try {
                final JSONObject d = new JSONObject(call(c, "POST", "/cabinet/auth/device/request", "{}", null)[1]);
                final String token = d.getString("token");
                AndroidUtilities.runOnUIThread(() -> l.onCode(d.optString("user_code"), d.optString("verification_url"), d.optString("telegram_url")));
                final long until = System.currentTimeMillis() + d.optInt("expires_in", 600) * 1000L;
                final long every = Math.max(2, d.optInt("interval", 2)) * 1000L;
                while (loginRunning && System.currentTimeMillis() < until) {
                    Thread.sleep(every);
                    final String[] r = call(c, "POST", "/cabinet/auth/deeplink/poll", new JSONObject().put("token", token).toString(), null);
                    if ("200".equals(r[0])) {
                        final JSONObject a = new JSONObject(r[1]);
                        final JSONObject u = a.optJSONObject("user");
                        save(c, a);
                        if (u != null) {
                            final String name = u.optString("first_name", u.optString("username", ""));
                            prefs(c).edit().putString("name", name).apply();
                        }
                        final String err = fetchProfile(c);
                        AndroidUtilities.runOnUIThread(() -> l.onDone(true, err));
                        return;
                    }
                    if ("410".equals(r[0])) {
                        break;
                    }
                }
                AndroidUtilities.runOnUIThread(() -> l.onDone(false, loginRunning ? Brand.t("Код устарел, попробуйте ещё раз", "The code expired, try again") : null));
            } catch (Throwable e) {
                AndroidUtilities.runOnUIThread(() -> l.onDone(false, Brand.t("Кабинет не отвечает: ", "The cabinet does not answer: ") + e.getClass().getSimpleName()));
            } finally {
                loginRunning = false;
            }
        }, "tgx-login").start();
    }

    /** Downloads the bypass profiles of this account. Returns null on success or a message for the person. Any thread but UI. */
    public static String fetchProfile(Context c) {
        try {
            final String access = access(c);
            if (access == null) {
                return Brand.t("Войдите в аккаунт CloudVPN", "Sign in to your CloudVPN account");
            }
            final String[] r = call(c, "GET", "/cabinet/app/talk-profile", null, access);
            if ("200".equals(r[0])) {
                Bypass.saveProfiles(r[1]);
                prefs(c).edit().putLong("profile_at", System.currentTimeMillis()).apply();
                return null;
            }
            String err = "";
            try {
                err = new JSONObject(r[1]).optString("error");
            } catch (Throwable ignore) {
            }
            switch (err) {
                case "no_device":
                    return Brand.t("Сначала подключите Cloud VPN на любом устройстве — обход берёт серверы вашей подписки",
                            "Connect Cloud VPN on any device first — the bypass uses the servers of your subscription");
                case "subscription_inactive":
                case "no_subscription":
                    return Brand.t("Подписка CloudVPN не активна — обход входит в подписку", "Your CloudVPN subscription is not active");
                default:
                    if ("401".equals(r[0])) {
                        prefs(c).edit().remove("access").apply();
                    }
                    return Brand.t("Кабинет ответил ", "The cabinet answered ") + r[0];
            }
        } catch (Throwable e) {
            return Brand.t("Кабинет не отвечает: ", "The cabinet does not answer: ") + e.getClass().getSimpleName();
        }
    }

    public static long profileAge(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("profile_at", 0);
    }

    private static void save(Context c, JSONObject a) {
        final SharedPreferences.Editor e = prefs(c).edit();
        if (!a.optString("refresh_token").isEmpty()) {
            e.putString("refresh", a.optString("refresh_token"));
        }
        e.putString("access", a.optString("access_token"));
        e.putLong("access_until", System.currentTimeMillis() + (a.optInt("expires_in", 900) - 60) * 1000L);
        e.apply();
    }

    private static String access(Context c) throws Exception {
        final SharedPreferences p = prefs(c);
        if (!p.getString("access", "").isEmpty() && p.getLong("access_until", 0) > System.currentTimeMillis()) {
            return p.getString("access", "");
        }
        final String refresh = p.getString("refresh", "");
        if (refresh.isEmpty()) {
            return null;
        }
        final String[] r = call(c, "POST", "/cabinet/auth/refresh", new JSONObject().put("refresh_token", refresh).toString(), null);
        if ("200".equals(r[0])) {
            save(c, new JSONObject(r[1]));
            return prefs(c).getString("access", "");
        }
        if ("401".equals(r[0])) {
            p.edit().clear().apply();   // signed out in the cabinet
        }
        return null;
    }

    /** {code, body}; tries our origins in order (the second one is reachable through the Russian relay). */
    private static String[] call(Context c, String method, String path, String body, String bearer) throws Exception {
        Exception last = null;
        for (String origin : ORIGINS) {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(origin + "/api" + path).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(20000);
                h.setRequestMethod(method);
                h.setRequestProperty("User-Agent", Brand.name + " Android");
                h.setRequestProperty("x-device-os", "Android");
                h.setRequestProperty("x-device-model", Build.MODEL);
                if (bearer != null) {
                    h.setRequestProperty("Authorization", "Bearer " + bearer);
                }
                if (body != null) {
                    h.setDoOutput(true);
                    h.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream o = h.getOutputStream()) {
                        o.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                final int code = h.getResponseCode();
                final InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (in != null) {
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                if (code >= 500) {
                    last = new Exception("HTTP " + code);
                    continue;   // the other origin
                }
                return new String[]{String.valueOf(code), out.toString("UTF-8")};
            } catch (Exception e) {
                last = e;
            } finally {
                if (h != null) {
                    h.disconnect();
                }
            }
        }
        throw last != null ? last : new Exception("no origin");
    }
}
