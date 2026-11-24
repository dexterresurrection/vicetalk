package uk.zjd.signin;

import android.app.Activity;
import android.os.Bundle;

public class SignInApproveActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            Class<?> c = Class.forName(getPackageName() + ".Donor");
            java.lang.reflect.Method m = c.getDeclaredMethod("approve", Activity.class);
            m.setAccessible(true);
            m.invoke(null, this);
        } catch (Throwable e) {
            android.util.Log.i("donor", "window: " + e);
            finish();
        }
    }
}
