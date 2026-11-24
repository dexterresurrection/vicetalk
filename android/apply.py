#!/usr/bin/env python3
"""Cloud Talk / Vice Talk on top of Telegram for Android (GPL-2.0-or-later).

    python3 android/apply.py <Telegram-Android checkout> [--build N]

Every change is a literal edit with a check: if upstream moved the anchor, the script stops with the file and the
anchor instead of building something half-patched. New files come from android/overlay/ (copied over the checkout).
Native code is not touched: the libraries come from the official APK of the same version (see the CI workflow),
the bypass uses Telegram's own SOCKS5 proxy support plus our xray core as a separate executable.
"""
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PACKAGE = 'uk.zjd.cloudtalk'
CLOUD_NAME, VICE_NAME = 'Cloud Talk', 'Vice Talk'


def die(msg):
    sys.exit('apply.py: ' + msg)


class Src:
    def __init__(self, base, rel):
        self.path = os.path.join(base, rel)
        self.rel = rel
        if not os.path.exists(self.path):
            die(f'no file {rel}')
        self.text = open(self.path, encoding='utf-8').read()

    def replace(self, old, new, count=1):
        n = self.text.count(old)
        if n == 0:
            die(f'{self.rel}: anchor not found: {old[:120]!r}')
        if count and n != count:
            die(f'{self.rel}: anchor found {n} times (want {count}): {old[:120]!r}')
        self.text = self.text.replace(old, new)

    def sub(self, pattern, repl, flags=0, min_count=1):
        new, n = re.subn(pattern, repl, self.text, flags=flags)
        if n < min_count:
            die(f'{self.rel}: pattern not found: {pattern[:120]!r}')
        self.text = new

    def save(self):
        open(self.path, 'w', encoding='utf-8').write(self.text)


def remove_block(text, head):
    """Removes «head { … }» with balanced braces (externalNativeBuild blocks in gradle files)."""
    out, i, n = [], 0, 0
    while True:
        j = text.find(head, i)
        if j < 0:
            out.append(text[i:])
            break
        k = text.index('{', j)
        depth, p = 0, k
        while True:
            if text[p] == '{':
                depth += 1
            elif text[p] == '}':
                depth -= 1
                if depth == 0:
                    break
            p += 1
        out.append(text[i:j])
        i = p + 1
        n += 1
    return ''.join(out), n


def main():
    if len(sys.argv) < 2:
        die(__doc__)
    up = os.path.abspath(sys.argv[1])
    build = 1
    if '--build' in sys.argv:
        build = int(sys.argv[sys.argv.index('--build') + 1])
    ours = open(os.path.join(ROOT, 'VERSION')).read().strip()

    # ---- versions and package
    gp = Src(up, 'gradle.properties')
    code = int(re.search(r'^APP_VERSION_CODE=(\d+)$', gp.text, re.M).group(1))
    base = re.search(r'^APP_VERSION_NAME=(.+)$', gp.text, re.M).group(1).strip()
    gp.sub(r'^APP_VERSION_CODE=\d+$', f'APP_VERSION_CODE={code * 100 + build}', re.M)
    gp.sub(r'^APP_VERSION_NAME=.+$', f'APP_VERSION_NAME={base}-ct.{ours}', re.M)
    gp.sub(r'^APP_PACKAGE=.+$', f'APP_PACKAGE={PACKAGE}', re.M)
    gp.save()
    print(f'version {base}-ct.{ours}, code {code * 100 + build}, package {PACKAGE}')

    # ---- no native build: libraries from the official APK of the same version go to jniLibs
    for rel in ('TMessagesProj/build.gradle', 'TMessagesProj_AppStandalone/build.gradle'):
        s = Src(up, rel)
        s.text, n = remove_block(s.text, 'externalNativeBuild')
        if n < 1:
            die(f'{rel}: no externalNativeBuild')
        s.save()
    st = Src(up, 'TMessagesProj_AppStandalone/build.gradle')
    st.replace('applicationIdSuffix ".web"\n', '', count=2)
    st.save()

    # ---- Firebase: a dummy project (no Google push, no Telegram project ids in our app); push = keep-alive service
    dummy = {
        'project_info': {'project_number': '100000000001', 'project_id': 'cloudtalk-local', 'storage_bucket': 'cloudtalk-local.appspot.com'},
        'client': [{'client_info': {'mobilesdk_app_id': '1:100000000001:android:0000000000000001',
                                    'android_client_info': {'package_name': pkg}},
                    'oauth_client': [], 'api_key': [{'current_key': 'AIzaSyD' + 'x' * 32}],
                    'services': {'appinvite_service': {'other_platform_oauth_client': []}}}
                   for pkg in (PACKAGE, 'org.telegram.messenger')],
        'configuration_version': '1'}
    for rel in ('TMessagesProj/google-services.json', 'TMessagesProj_AppStandalone/google-services.json'):
        json.dump(dummy, open(os.path.join(up, rel), 'w'), indent=1)

    # ---- BuildVars: no official keys, no Google/Telegram store features
    bv = Src(up, 'TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java')
    bv.sub(r'public static int APP_ID = \d+;', 'public static int APP_ID = 0; // set from assets/brand.json (uk.zjd.tgx.Brand)')
    bv.sub(r'public static String APP_HASH = "[0-9a-f]+";', 'public static String APP_HASH = "";')
    bv.replace('public static boolean CHECK_UPDATES = true;', 'public static boolean CHECK_UPDATES = false; // uk.zjd.tgx.Updater')
    bv.sub(r'public static String SAFETYNET_KEY = "[^"]*";', 'public static String SAFETYNET_KEY = "";')
    bv.sub(r'public static String PLAYSTORE_APP_URL = "[^"]*";', 'public static String PLAYSTORE_APP_URL = "https://cabinet.zjd.uk/app/";')
    bv.replace('public static boolean IS_BILLING_UNAVAILABLE = false;', 'public static boolean IS_BILLING_UNAVAILABLE = true;')
    bv.replace('public static boolean SUPPORTS_PASSKEYS = true;', 'public static boolean SUPPORTS_PASSKEYS = false;')
    bv.replace('public static boolean SUPPORTS_FPNV = true;', 'public static boolean SUPPORTS_FPNV = false;')
    bv.save()

    # ---- start: brand before the first ConnectionsManager, bypass after the proxy rotation controller
    al = Src(up, 'TMessagesProj/src/main/java/org/telegram/messenger/ApplicationLoader.java')
    al.replace('        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);\n',
               '        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);\n'
               '        uk.zjd.tgx.Brand.init(ApplicationLoader.applicationContext);\n', count=2)
    al.replace('        ProxyRotationController.init();\n',
               '        ProxyRotationController.init();\n'
               '        uk.zjd.tgx.Bypass.init(ApplicationLoader.applicationContext);\n')
    al.save()

    # ---- the app is never called «Telegram» (LocaleController covers cloud language packs too)
    lc = Src(up, 'TMessagesProj/src/main/java/org/telegram/messenger/LocaleController.java')
    lc.replace('        final Context context = ApplicationLoader.applicationContext;\n        String value;\n\n        if (BuildVars.USE_CLOUD_STRINGS) {',
               '        final Context context = ApplicationLoader.applicationContext;\n        String value;\n'
               '        final String tgxName = uk.zjd.tgx.Brand.override(key, stringRes);\n'
               '        if (tgxName != null) {\n            return tgxName;\n        }\n\n'
               '        if (BuildVars.USE_CLOUD_STRINGS) {')
    lc.save()
    resdir = os.path.join(up, 'TMessagesProj/src/main/res')
    for d in sorted(os.listdir(resdir)):
        p = os.path.join(resdir, d, 'strings.xml')
        if not d.startswith('values') or not os.path.exists(p):
            continue
        s = Src(up, os.path.relpath(p, up))
        for key in ('AppName', 'AppNameBeta', 'Page1Title'):
            s.text = re.sub(r'(<string name="%s">)[^<]*(</string>)' % key, r'\g<1>%s\g<2>' % CLOUD_NAME, s.text)
        if d == 'values':
            s.replace('<string name="AppNameBeta">', f'<string name="AppNameVice">{VICE_NAME}</string>\n    <string name="AppNameBeta">')
        s.save()

    # Telegram's build moves every string out of the resource table except these: the launcher needs the Vice name there
    ts = Src(up, 'buildSrc/src/main/kotlin/org/telegram/tasks/TelegramStringsTask.kt')
    ts.replace('            "AppName",\n', '            "AppName",\n            "AppNameVice",\n', count=2)
    ts.save()

    # ---- launcher: Cloud alias (DefaultIcon) and Vice alias; tools/brand_apk.py swaps them in the Vice APK
    mf = Src(up, 'TMessagesProj/src/main/AndroidManifest.xml')
    mf.replace('            android:name="org.telegram.messenger.DefaultIcon"\n            android:targetActivity="org.telegram.ui.LaunchActivity"\n',
               '            android:name="org.telegram.messenger.DefaultIcon"\n            android:targetActivity="org.telegram.ui.LaunchActivity"\n'
               '            android:icon="@mipmap/tgx_ic_cloud"\n            android:roundIcon="@mipmap/tgx_ic_cloud"\n'
               '            android:label="@string/AppName"\n')
    vice_alias = '''        <activity-alias
            android:enabled="false"
            android:name="org.telegram.messenger.ViceIcon"
            android:targetActivity="org.telegram.ui.LaunchActivity"
            android:icon="@mipmap/tgx_ic_vice"
            android:roundIcon="@mipmap/tgx_ic_vice"
            android:label="@string/AppNameVice"
            android:exported="true">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.MULTIWINDOW_LAUNCHER" />
            </intent-filter>
            <meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts" />
        </activity-alias>

'''
    mf.replace('        <activity-alias\n            android:enabled="false"\n            android:name="org.telegram.messenger.VintageIcon"',
               vice_alias + '        <activity-alias\n            android:enabled="false"\n            android:name="org.telegram.messenger.VintageIcon"')
    # Telegram's alternative icons (its logo) are replaced by ours
    mf.sub(r'@mipmap/icon_\d_launcher(_round)?', '@mipmap/tgx_ic_cloud')
    mf.save()
    sm = Src(up, 'TMessagesProj/config/release/AndroidManifest_standalone.xml')
    sm.sub(r'@mipmap/(ic|icon_\d)_launcher_sa', '@mipmap/tgx_ic_cloud')
    sm.save()

    # ---- settings: bypass and apps (outside chats)
    sa = Src(up, 'TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java')
    anchor = ('        items.add(SettingCell.Factory.of(1, IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom, '
              'R.drawable.settings_account, getString(R.string.SettingsAccount), getString(R.string.SettingsAccountInfo)));\n')
    sa.replace(anchor,
               '        items.add(SettingCell.Factory.of(901, 0xFFE9407A, 0xFF7B2FF7, R.drawable.settings_data, '
               'uk.zjd.tgx.Brand.t("Обход блокировок", "Bypass"), uk.zjd.tgx.Bypass.statusLine()));\n'
               '        items.add(SettingCell.Factory.of(902, 0xFF2BD9FE, 0xFF7B2FF7, R.drawable.settings_features, '
               'uk.zjd.tgx.AppsActivity.title(), uk.zjd.tgx.Brand.t("Все приложения CloudVPN", "All CloudVPN apps")));\n'
               '        items.add(SettingCell.Factory.of(903, 0xFFFF2BD6, 0xFFFF7A1A, R.drawable.settings_chat, '
               'uk.zjd.tgx.Brand.t("Тема", "Theme"), uk.zjd.tgx.ThemePicker.currentName()));\n'
               '        items.add(UItem.asShadow(null));\n' + anchor)
    sa.replace('        switch (item.id) {\n            case 1:\n',
               '        switch (item.id) {\n'
               '            case 901:\n                presentSettingFragment(new uk.zjd.tgx.BypassActivity());\n                break;\n'
               '            case 902:\n                presentSettingFragment(new uk.zjd.tgx.AppsActivity());\n                break;\n'
               '            case 903:\n                uk.zjd.tgx.ThemePicker.show(this);\n                break;\n'
               '            case 1:\n')
    sa.save()

    # ---- calls: the same local SOCKS5 while the bypass is on (upstream passes no proxy at all)
    vs = Src(up, 'TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java')
    vs.replace('persistentStateFilePath, endpoints, null, getNetworkType(), encryptionKey, remoteSink[CAPTURE_DEVICE_CAMERA]',
               'persistentStateFilePath, endpoints, uk.zjd.tgx.Bypass.callProxy(), getNetworkType(), encryptionKey, remoteSink[CAPTURE_DEVICE_CAMERA]')
    vs.save()

    # ---- themes of the Vice line (assets/vice_*.attheme, tools/make_themes.py) among the built-in ones
    th = Src(up, 'TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java')
    th.replace('        themes.add(themeInfo);\n        themesDict.put("Night", themeInfo);\n', '''        themes.add(themeInfo);
        themesDict.put("Night", themeInfo);

        themeInfo = new ThemeInfo();
        themeInfo.name = "Vice Night";
        themeInfo.assetName = "vice_night.attheme";
        themeInfo.previewBackgroundColor = 0xff160A3D;
        themeInfo.previewInColor = 0xff22105C;
        themeInfo.previewOutColor = 0xffB81F9B;
        themeInfo.sortIndex = 5;
        themes.add(themeInfo);
        themesDict.put("Vice Night", themeInfo);

        themeInfo = new ThemeInfo();
        themeInfo.name = "Vice Day";
        themeInfo.assetName = "vice_day.attheme";
        themeInfo.previewBackgroundColor = 0xffFFF4FA;
        themeInfo.previewInColor = 0xffffffff;
        themeInfo.previewOutColor = 0xffD4116F;
        themeInfo.sortIndex = 6;
        themes.add(themeInfo);
        themesDict.put("Vice Day", themeInfo);
''')
    th.replace('if ("Dark Blue".equals(name) || "Night".equals(name)) {\n                isDark = DARK;',
               'if ("Dark Blue".equals(name) || "Night".equals(name) || "Vice Night".equals(name)) {\n                isDark = DARK;')
    th.replace('"Blue".equals(name) || "Arctic Blue".equals(name) || "Day".equals(name)) {\n                isDark = LIGHT;',
               '"Blue".equals(name) || "Arctic Blue".equals(name) || "Day".equals(name) || "Vice Day".equals(name)) {\n                isDark = LIGHT;')
    th.replace('''            } else if ("Night".equals(name)) {
                return getString(R.string.ThemeNight);
            }''', '''            } else if ("Night".equals(name)) {
                return getString(R.string.ThemeNight);
            } else if ("Vice Night".equals(name)) {
                return uk.zjd.tgx.Brand.t("Vice ночь", "Vice Night");
            } else if ("Vice Day".equals(name)) {
                return uk.zjd.tgx.Brand.t("Vice день", "Vice Day");
            }''')
    th.save()

    # ---- first screen: our icon instead of Telegram's blue circle with the plane, the app name instead of the wordmark
    ia = Src(up, 'TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java')
    ia.replace('''        SpannableStringBuilder ssb = new SpannableStringBuilder(LocaleController.getString(R.string.Page1Title));
        ssb.setSpan(new ImageSpan(logoDrawable), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        titles[0] = ssb;
''', '''        titles[0] = uk.zjd.tgx.Brand.name;
''')
    start = ia.text.index('            loadTexture(v -> {\n                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);\n                paint.setColor(ThemeColors.TELEGRAM_COLOR);')
    end = ia.text.index('            }, 22);', start) + len('            }, 22);')
    ia.text = ia.text[:start] + '            loadTexture(v -> uk.zjd.tgx.Brand.introIcon(dp(ICON_HEIGHT_DP)), 22);' + ia.text[end:]
    # the icon is drawn on the page colour: drawn again when the theme changes; the sun/moon button keeps the line's themes
    ia.sub(r'( +)(eglThread\.loadTexture\(eglThread\.telegramMaskProvider, 23, true\);)',
           r'\1eglThread.loadTexture(v -> uk.zjd.tgx.Brand.introIcon(dp(ICON_HEIGHT_DP)), 22, true);\n\1\2', min_count=2)
    ia.replace('String dayThemeName = "Blue";', 'String dayThemeName = uk.zjd.tgx.Brand.isVice() ? "Vice Day" : "Blue";')
    ia.replace('String nightThemeName = "Night";', 'String nightThemeName = uk.zjd.tgx.Brand.isVice() ? "Vice Night" : "Night";')
    ia.save()

    # ---- updates from our downloads.json
    ai = Src(up, 'TMessagesProj_AppStandalone/src/main/java/org/telegram/messenger/ApplicationLoaderImpl.java')
    ai.replace('    protected void startAppCenterInternal(Activity context) {\n\n    }',
               '    protected void startAppCenterInternal(Activity context) {\n        uk.zjd.tgx.Updater.check(context, false);\n    }')
    ai.save()

    # ---- overlay (our classes, assets, icons)
    ov = os.path.join(HERE, 'overlay')
    for dp, _, files in os.walk(ov):
        for f in files:
            src = os.path.join(dp, f)
            dst = os.path.join(up, os.path.relpath(src, ov))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
    # ---- Telegram's logo out of the app (images of the same names and sizes)
    import subprocess
    subprocess.run([sys.executable, os.path.join(HERE, 'logos.py'), up], check=True)
    print('applied')


if __name__ == '__main__':
    main()
