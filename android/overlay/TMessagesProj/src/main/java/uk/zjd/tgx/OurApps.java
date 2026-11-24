/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Our apps: package → SHA-256 of the signing certificate of the published APK. Every app has its own key, so a
 * signature-level permission cannot work between them: the package and its certificate are compared with this list.
 */
final class OurApps {
    /** Certificate of Cloud Talk / Vice Talk releases. */
    private static final String RELEASE = "91b9a7367f6689a23390a56233ba5f2bdbc92d9fdbcfa01feed3e7d6b8214a89";
    private static final String[][] LIST = {
            {"uk.zjd.cloudvpn", "908609876adf946eeb4da928052dd2d60dde28945a24d377d958dcd947d1628d"},
            {"uk.zjd.cloudmessenger", "f1000772e4bead5cff1763bad125b2a4669020d60903711c86723f664c1f56b3"},
            {"uk.zjd.cloudsocial", "98e916eac3b741263dae670df33cc6db5cca33d2b0db755654728071716adfd5"},
            {"uk.zjd.vicewave", "ced42506a1cf463a21898de4a538daeae29480e57e3d3c44f92194f8b864ada3"},
            {"uk.zjd.cloudmail", "e10fc0ae672016a12eb41d754e33c45c0ebb52cc4594a1aec406d287f698b68a"},
            {"uk.zjd.cloudbrowser", "39d7d0153cbe1df518630e5b4450633276afe13a57fbaab4893a376f8bfb59c7"},
            {"uk.zjd.cloudlove", "c7092f76b166d5e8ebb5ab511ad036dbb474aa3cc6971b2d3a845fbd3de2382d"},
            {"uk.zjd.cloudwallet", "165f3b2e550420b8b3e695a3fea660cf9ee0cb050c212f4863121b0b53ff9d26"},
            {"uk.zjd.cloudtorrent", "30ef3b089bd2cab099dd5b8126392768c8271da6866efaf6439d9555e4d5e646"},
    };

    private OurApps() {
    }

    /** A build signed with another key (CI) may name its own test apps and cabinet in brand.json; a release never does. */
    static boolean testBuild(Context c) {
        final String own = certOf(c, c.getPackageName());
        return own != null && !RELEASE.equals(own);
    }

    static Map<String, String> all(Context c) {
        final Map<String, String> out = new LinkedHashMap<>();
        final JSONArray test = Brand.json.optJSONArray("test_apps");
        if (test != null && testBuild(c)) {
            for (int i = 0; i < test.length(); i++) {
                final JSONObject o = test.optJSONObject(i);
                if (o != null) {
                    out.put(o.optString("pkg"), o.optString("cert"));
                }
            }
            return out;
        }
        for (String[] a : LIST) {
            out.put(a[0], a[1]);
        }
        return out;
    }

    /** The package is in the list and is signed with exactly that certificate. */
    static boolean verify(Context c, String pkg) {
        final String want = pkg == null ? null : all(c).get(pkg);
        return want != null && want.equals(certOf(c, pkg));
    }

    /** SHA-256 of the current signing certificate; several signers or no such package — null. */
    static String certOf(Context c, String pkg) {
        try {
            final PackageManager pm = c.getPackageManager();
            final Signature[] s;
            if (Build.VERSION.SDK_INT >= 28) {
                final PackageInfo i = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                s = i.signingInfo == null || i.signingInfo.hasMultipleSigners() ? null : i.signingInfo.getApkContentsSigners();
            } else {
                s = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (s == null || s.length != 1) {
                return null;
            }
            final StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(s[0].toByteArray())) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }
}
