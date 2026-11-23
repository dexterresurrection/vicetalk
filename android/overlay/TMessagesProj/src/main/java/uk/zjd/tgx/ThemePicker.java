/*
 * Cloud Talk / Vice Talk — unofficial Telegram client by CloudVPN.
 * Licensed under GNU GPL v. 2 or later, like the Telegram for Android sources it is based on.
 */
package uk.zjd.tgx;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

/** Settings → «Тема»: the looks of both lines in one short list (the full theme settings stay in «Chat Settings»). */
public final class ThemePicker {
    private static final String[] KEYS = {"Vice Night", "Vice Day", "Night", "Day", "Dark Blue", "Blue"};

    private ThemePicker() {
    }

    public static String currentName() {
        try {
            final Theme.ThemeInfo t = Theme.getActiveTheme();
            return t != null ? t.getName() : "";
        } catch (Throwable e) {
            return "";
        }
    }

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final CharSequence[] names = new CharSequence[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            final Theme.ThemeInfo t = Theme.getTheme(KEYS[i]);
            names[i] = t != null ? t.getName() : KEYS[i];
        }
        new AlertDialog.Builder(fragment.getParentActivity())
                .setTitle(Brand.t("Тема", "Theme"))
                .setItems(names, (dialog, which) -> apply(KEYS[which]))
                .show();
    }

    private static void apply(String key) {
        final Theme.ThemeInfo t = Theme.getTheme(key);
        if (t == null) {
            return;
        }
        ApplicationLoader.applicationContext.getSharedPreferences("tgx_brand", Context.MODE_PRIVATE).edit()
                .putBoolean("theme_chosen", true).apply();
        // follow the choice, not the system's night mode
        Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
        Theme.saveAutoNightThemeConfig();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.needSetDayNightTheme, t, false, null, -1));
    }
}
