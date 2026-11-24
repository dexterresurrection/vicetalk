// A test app that plays «our app with an account» in the emulator check of shared sign-in (CI only, never published).
// @…@ is replaced by build_donors.sh: the package, the name, the service of its account («» — an app older than the field).
package @PKG@;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Donor extends ContentProvider {
    static final String ORIGIN = "@ORIGIN@", NAME = "@NAME@", CABINET = "http://10.0.2.2:8081";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle in) {
        Log.i("donor", "@PKG@ asked by " + getCallingPackage() + " for " + (in != null ? in.getString("origin") : null));
        Bundle b = new Bundle();
        b.putBoolean("signed_in", true);
        b.putString("name", NAME);
        if (!ORIGIN.isEmpty()) {
            b.putString("origin", ORIGIN);
        }
        return b;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri u) {
        return null;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }

    static void approve(Activity a) {
        final Intent i = a.getIntent();
        Log.i("donor", "@PKG@ window for " + a.getCallingPackage() + ", extras " + new java.util.ArrayList<String>(i.getExtras().keySet()));
        a.setResult(Activity.RESULT_CANCELED);
        if (!"uk.zjd.cloudtalk".equals(a.getCallingPackage())) {
            a.finish();
            return;
        }
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Donor").setMessage("Sign in?").setCancelable(false)
                .setPositiveButton("Allow", (d, w) -> new Thread(() -> {
                    try {
                        HttpURLConnection h = (HttpURLConnection) new URL(CABINET + "/api/cabinet/auth/device/approve").openConnection();
                        h.setRequestMethod("POST");
                        h.setDoOutput(true);
                        h.setRequestProperty("Authorization", "Bearer donor");
                        try (OutputStream o = h.getOutputStream()) {
                            o.write(("{\"user_code\":\"" + i.getStringExtra("code") + "\"}").getBytes("UTF-8"));
                        }
                        Log.i("donor", "@PKG@ approve " + h.getResponseCode());
                        a.runOnUiThread(() -> {
                            a.setResult(Activity.RESULT_OK);
                            a.finish();
                        });
                    } catch (Throwable e) {
                        Log.i("donor", "@PKG@ approve " + e);
                        a.runOnUiThread(a::finish);
                    }
                }).start())
                .setNegativeButton("Deny", (d, w) -> a.finish()).show();
    }
}
