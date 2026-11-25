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
 * The account the bypass comes from (it is part of the subscription): CloudVPN (cabinet.zjd.uk) or, in the Vice line, a
 * choice of Vice VPN (vice.zjd.uk) and CloudVPN. One session at a time, its account is written next to it; the account
 * can be changed only without a session, and the change leaves nothing of the former one. A token goes only to the
 * addresses of its own account: every request takes the account and the token together.
 *
 * Sign-in by a device code confirmed by another app of ours on this phone (SharedSignIn), in the cabinet or in the bot:
 * POST /cabinet/auth/device/request → the person confirms the code → POST /cabinet/auth/deeplink/poll returns the tokens.
 * The bypass servers come from GET /cabinet/app/talk-profile (Xray outbounds of the person's own subscription, no extra
 * device slot, plus our MTProto proxies).
 */
public final class Account {
    public static final String CLOUD = "cloud", VICE = "vice";
    private static final Object LOCK = new Object();      // prefs and gen
    private static final Object REFRESH = new Object();   // one refresh at a time
    private static int gen;                               // grows with every sign-in, sign-out and change of the account
    private static volatile int loginRun;                 // 0 — no sign-in in progress, otherwise the number of the current one
    private static int loginCount;

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

    /** Whose session it is; without a session — the one chosen for the sign-in. A session without a mark is CloudVPN. */
    public static String current(Context c) {
        final String stored = prefs(c).getString("account", "");
        if (loggedIn(c)) {
            return VICE.equals(stored) ? VICE : CLOUD;
        }
        return Brand.isVice() && !CLOUD.equals(stored) ? VICE : CLOUD;
    }

    /** What there is to choose from: the Cloud line has one account. */
    public static String[] choices() {
        return Brand.isVice() ? new String[]{VICE, CLOUD} : new String[]{CLOUD};
    }

    public static String title(String account) {
        return VICE.equals(account) ? "Vice VPN" : "CloudVPN";
    }

    public static String title(Context c) {
        return title(current(c));
    }

    /** Called once per line at start: an app that stood as the Cloud line before keeps the CloudVPN account by default. */
    static void lineChanged(Context c, boolean firstStart) {
        final SharedPreferences p = prefs(c);
        if (!firstStart && !p.contains("account")) {
            p.edit().putString("account", CLOUD).apply();
        }
    }

    /** The choice before the sign-in; with a session nothing changes (sign out first). */
    public static boolean choose(Context c, String account) {
        synchronized (LOCK) {
            if (loggedIn(c) || account.equals(current(c))) {
                return !loggedIn(c);
            }
            loginRun = 0;
            gen++;
            prefs(c).edit().clear().putString("account", account).apply();
        }
        Bypass.clearProfiles();
        Bypass.note("account: " + account);
        return true;
    }

    static String origin(Context c) {
        return VICE.equals(current(c)) ? SharedSignIn.VICE : SharedSignIn.CLOUD;
    }

    /** Addresses of an account's cabinet (the mirror of CloudVPN is reachable through the Russian relay). */
    private static String[] origins(Context c, String account) {
        final String test = Brand.json.optString(VICE.equals(account) ? "test_origin_vice" : "test_origin", "");
        if (!test.isEmpty() && OurApps.testBuild(c)) {
            return new String[]{test};
        }
        return VICE.equals(account) ? new String[]{SharedSignIn.VICE} : new String[]{SharedSignIn.CLOUD, SharedSignIn.CLOUD_MIRROR};
    }

    public static String userName(Context c) {
        return prefs(c).getString("name", "");
    }

    public static void logout(Context c) {
        synchronized (LOCK) {
            final String account = current(c);   // the next sign-in offers the same account
            loginRun = 0;
            gen++;
            prefs(c).edit().clear().putString("account", account).apply();
        }
        Bypass.clearProfiles();
    }

    public static void cancelLogin() {
        loginRun = 0;
    }

    /** Device-code sign-in to the chosen account; listener calls arrive on the UI thread, none of them after cancelLogin(). */
    public static void startLogin(Context ctx, LoginListener l) {
        final Context c = ctx.getApplicationContext();
        final int run = ++loginCount;
        loginRun = run;
        final String account = current(c);
        new Thread(() -> {
            String error;
            boolean ok = false;
            try {
                final JSONObject d = new JSONObject(call(c, account, "POST", "/cabinet/auth/device/request", "{}", null)[1]);
                final String token = d.getString("token");
                report(run, () -> l.onCode(d.optString("user_code"), d.optString("verification_url"), d.isNull("telegram_url") ? "" : d.optString("telegram_url")));
                final long until = System.currentTimeMillis() + d.optInt("expires_in", 600) * 1000L;
                final long every = Math.max(2, d.optInt("interval", 2)) * 1000L;
                error = Brand.t("Код устарел, попробуйте ещё раз", "The code expired, try again");
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(every);
                    if (loginRun != run) {
                        return;
                    }
                    final String[] r = call(c, account, "POST", "/cabinet/auth/deeplink/poll", new JSONObject().put("token", token).toString(), null);
                    if ("200".equals(r[0])) {
                        final JSONObject a = new JSONObject(r[1]);
                        synchronized (LOCK) {
                            if (loginRun != run) {
                                return;   // cancelled or another account chosen meanwhile: the new session is not kept
                            }
                            gen++;
                            final JSONObject u = a.optJSONObject("user");
                            prefs(c).edit().clear().putString("account", account)
                                    .putString("name", u == null ? "" : u.optString("first_name", u.optString("username", ""))).apply();
                            save(c, a);
                        }
                        ok = true;
                        Bypass.note("sign-in: done (" + account + ")");
                        error = fetchProfile(c);
                        break;
                    }
                    if ("410".equals(r[0])) {
                        break;
                    }
                }
            } catch (Throwable e) {
                error = Brand.t("Кабинет не отвечает: ", "The cabinet does not answer: ") + e.getClass().getSimpleName();
            }
            final boolean done = ok;
            final String err = error;
            report(run, () -> {
                loginRun = 0;
                l.onDone(done, err);
            });
        }, "tgx-login").start();
    }

    private static void report(int run, Runnable r) {
        AndroidUtilities.runOnUIThread(() -> {
            if (loginRun == run) {
                r.run();
            }
        });
    }

    /** Downloads the bypass servers of this account. Returns null on success or a message for the person. Any thread but UI. */
    public static String fetchProfile(Context c) {
        try {
            final String account;
            final int g;
            synchronized (LOCK) {
                account = current(c);
                g = gen;
            }
            final String access = access(c, account, g);
            if (access == null) {
                return Brand.t("Войдите в аккаунт " + title(account), "Sign in to your " + title(account) + " account");
            }
            final String[] r = call(c, account, "GET", "/cabinet/app/talk-profile", null, access);
            synchronized (LOCK) {
                if (gen != g) {
                    return null;   // signed out or another account meanwhile: these servers are not used
                }
                if ("200".equals(r[0])) {
                    Bypass.saveProfiles(r[1]);
                    prefs(c).edit().putLong("profile_at", System.currentTimeMillis()).apply();
                    return null;
                }
                if ("401".equals(r[0])) {
                    prefs(c).edit().remove("access").apply();
                }
            }
            String err = "";
            try {
                err = new JSONObject(r[1]).optString("error");
            } catch (Throwable ignore) {
            }
            switch (err) {
                case "no_device":
                    return Brand.t("Сначала подключите " + title(account) + " на любом устройстве — обход берёт серверы вашей подписки",
                            "Connect " + title(account) + " on any device first — the bypass uses the servers of your subscription");
                case "subscription_inactive":
                case "no_subscription":
                    return Brand.t("Подписка " + title(account) + " не активна — обход входит в подписку", "Your " + title(account) + " subscription is not active");
                default:
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

    /** The access token of exactly that session (account + generation); null — it is not there any more. */
    private static String access(Context c, String account, int g) throws Exception {
        synchronized (REFRESH) {
            final String refresh;
            synchronized (LOCK) {
                if (gen != g) {
                    return null;
                }
                final SharedPreferences p = prefs(c);
                if (!p.getString("access", "").isEmpty() && p.getLong("access_until", 0) > System.currentTimeMillis()) {
                    return p.getString("access", "");
                }
                refresh = p.getString("refresh", "");
            }
            if (refresh.isEmpty()) {
                return null;
            }
            final String[] r = call(c, account, "POST", "/cabinet/auth/refresh", new JSONObject().put("refresh_token", refresh).put("rotate", true).toString(), null);
            synchronized (LOCK) {
                if (gen != g) {
                    return null;
                }
                if ("200".equals(r[0])) {
                    final JSONObject a = new JSONObject(r[1]);
                    save(c, a);
                    return a.optString("access_token");
                }
                if (!"401".equals(r[0])) {
                    return null;   // no connection or a fault of the cabinet is not a sign-out
                }
                gen++;   // signed out in the cabinet
                prefs(c).edit().clear().putString("account", account).apply();
            }
            Bypass.clearProfiles();
            return null;
        }
    }

    /** {code, body}; the addresses of that account in order. */
    private static String[] call(Context c, String account, String method, String path, String body, String bearer) throws Exception {
        Exception last = null;
        for (String origin : origins(c, account)) {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL(origin + "/api" + path).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(20000);
                h.setRequestMethod(method);
                h.setInstanceFollowRedirects(false);   // a token never follows a redirect to another address
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
