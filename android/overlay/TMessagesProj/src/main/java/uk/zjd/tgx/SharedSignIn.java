/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Sign-in through another app of ours on this phone (only the asking side). No tokens travel between the apps: we take
 * an ordinary device code from our cabinet, the other app asks the person and confirms that code with its own session,
 * and our usual poll gets our own session. Nothing else is asked for: no keys (the request carries no public key).
 */
public final class SharedSignIn {
    private static final String ACTION = "uk.zjd.signin.APPROVE";
    private static final String APPROVE = "uk.zjd.signin.SignInApproveActivity";
    /** Accounts of the two services are separate: a sign-in to one means nothing for the other. */
    static final String CLOUD = "https://cabinet.zjd.uk", CLOUD_MIRROR = "https://cabinet.0nn.us", VICE = "https://vice.zjd.uk";

    public static final class Donor {
        final String pkg, label, name, origin;

        Donor(String pkg, String label, String name, String origin) {
            this.pkg = pkg;
            this.label = label;
            this.name = name;
            this.origin = origin;
        }
    }

    private SharedSignIn() {
    }

    /** Which account an address belongs to; an unknown address equals nothing, not even itself. */
    private static String account(String origin) {
        if (CLOUD.equals(origin) || CLOUD_MIRROR.equals(origin)) {
            return "cloud";
        }
        return VICE.equals(origin) ? "vice" : null;
    }

    /** Our apps that are signed in to the account chosen here. Any thread but UI. */
    public static List<Donor> donors(Context c) {
        final String origin = Account.origin(c);
        final List<Donor> out = new ArrayList<>();
        for (String pkg : OurApps.all(c).keySet()) {
            if (pkg.equals(c.getPackageName())) {
                continue;
            }
            final Donor d = ask(c, pkg, origin);
            if (d != null) {
                out.add(d);
            }
        }
        Bypass.note("sign-in: " + out.size() + " app(s) can confirm");
        return out;
    }

    /** Asks one app again right before its window opens: it could have been replaced or signed out meanwhile. */
    public static boolean stillThere(Context c, Donor d) {
        return ask(c, d.pkg, d.origin) != null;
    }

    private static Donor ask(Context c, String pkg, String origin) {
        try {
            // the certificate is compared before the first contact: a fake app does not learn that it was asked
            if (!OurApps.verify(c, pkg)) {
                return null;
            }
            final PackageManager pm = c.getPackageManager();
            final String authority = pkg + ".signin";
            final ProviderInfo p = pm.resolveContentProvider(authority, 0);
            if (p == null || !pkg.equals(p.packageName)) {
                return null;
            }
            final Bundle in = new Bundle();
            in.putString("origin", origin);
            final Bundle b;
            final ContentProviderClient client = c.getContentResolver().acquireUnstableContentProviderClient(Uri.parse("content://" + authority));
            if (client == null) {
                return null;
            }
            try {
                b = client.call("state", null, in);
            } finally {
                client.release();
            }
            if (b == null || !b.getBoolean("signed_in")) {
                return null;
            }
            // an app older than this field knows nothing about the second service and answers for the first one
            final String theirs = account(b.getString("origin", CLOUD)), ours = account(origin);
            if (ours == null || !ours.equals(theirs)) {
                return null;
            }
            return new Donor(pkg, clean(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()), clean(b.getString("name")), origin);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        final String t = s.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /** For startActivityForResult: the other app checks our certificate, asks the person once and confirms the code. */
    public static Intent approveIntent(Donor d, String userCode) {
        return new Intent(ACTION).setComponent(new ComponentName(d.pkg, APPROVE))
                .putExtra("code", userCode).putExtra("origin", d.origin);
    }

    /** Why the other app did not confirm, for the person. */
    public static String error(Intent data) {
        if (data != null && data.getBooleanExtra("no_account", false)) {
            return Brand.t("В том приложении вход уже кончился", "That app is not signed in any more");
        }
        final String e = data != null ? clean(data.getStringExtra("error")) : "";
        return e.isEmpty() ? Brand.t("Вход не подтверждён", "The sign-in was not confirmed") : e;
    }
}
