#!/usr/bin/env python3
"""Cloud Talk / Vice Talk on top of Telegram Desktop (GPL-3.0 with the OpenSSL exception).

    python3 desktop/apply.py <tdesktop checkout>

Literal edits with checks (the script stops if upstream moved an anchor) + new files from desktop/overlay/.
One build serves both lines: the line, the name and the api keys come from brand.json next to the executable
(written by our installer), so CMake gets TDESKTOP_API_TEST only to pass its own check — config.h no longer uses it.
Configure with: -D TDESKTOP_API_TEST=ON -D DESKTOP_APP_DISABLE_AUTOUPDATE=ON -D DESKTOP_APP_DISABLE_CRASH_REPORTS=ON
"""
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APP_GUID = '{6B1E2F1C-4A3D-4C6E-9C1B-CD1A7E0B5A21}'


def die(msg):
    sys.exit('desktop/apply.py: ' + msg)


class Src:
    def __init__(self, base, rel):
        self.path = os.path.join(base, rel)
        self.rel = rel
        if not os.path.exists(self.path):
            die(f'no file {rel}')
        self.text = open(self.path, encoding='utf-8').read()   # a Windows checkout has CRLF: read as LF

    def replace(self, old, new, count=1):
        n = self.text.count(old)
        if n == 0:
            die(f'{self.rel}: anchor not found: {old[:140]!r}')
        if count and n != count:
            die(f'{self.rel}: anchor found {n} times (want {count}): {old[:140]!r}')
        self.text = self.text.replace(old, new)

    def sub(self, pattern, repl, flags=0, min_count=1):
        new, n = re.subn(pattern, repl, self.text, flags=flags)
        if n < min_count:
            die(f'{self.rel}: pattern not found: {pattern[:140]!r}')
        self.text = new

    def save(self):
        open(self.path, 'w', encoding='utf-8', newline='\n').write(self.text)


def main():
    if len(sys.argv) < 2:
        die(__doc__)
    up = os.path.abspath(sys.argv[1])
    S = 'Telegram/SourceFiles/'

    # ---- api keys at run time (brand.json), never the official ones, never compiled in
    c = Src(up, S + 'config.h')
    start = c.text.index('#if defined TDESKTOP_API_ID && defined TDESKTOP_API_HASH')
    end = c.text.index('#endif // TDESKTOP_API_ID && TDESKTOP_API_HASH') + len('#endif // TDESKTOP_API_ID && TDESKTOP_API_HASH')
    c.text = c.text[:start] + ('// Cloud Talk: api_id / api_hash come from brand.json next to the executable (tgx/tgx.cpp).\n'
                               '#include "tgx/tgx.h"\n'
                               '#define ApiId (Tgx::ApiIdValue())\n'
                               '#define ApiHash (Tgx::ApiHashValue())') + c.text[end:]
    c.save()

    # ---- names: not «Telegram Desktop», own GUID, own data folder, no migration from the official app's folders
    v = Src(up, S + 'core/version.h')
    v.sub(r'constexpr auto AppId = "\{[0-9A-F-]+\}"_cs;', f'constexpr auto AppId = "{APP_GUID}"_cs;')
    v.replace('constexpr auto AppNameOld = "Telegram Win (Unofficial)"_cs;', 'constexpr auto AppNameOld = "Cloud Talk Old"_cs;')
    v.replace('constexpr auto AppName = "Telegram Desktop"_cs;', '#include "tgx/tgx.h"\ninline constexpr Tgx::AppNameValue AppName{}; // brand name at run time')
    v.replace('constexpr auto AppFile = "Telegram"_cs;', 'constexpr auto AppFile = "CloudTalk"_cs;')
    v.save()
    w = Src(up, S + 'platform/win/specific_win.cpp')
    w.replace("return appData.absolutePath() + '/' + AppName.utf16() + '/';", "return appData.absolutePath() + u\"/Cloud Talk/\"_q;")
    w.replace('"\\\\Telegram.lnk"', '"\\\\CloudTalk.lnk"', count=2)
    w.save()
    lx = Src(up, S + 'platform/linux/specific_linux.cpp')
    lx.replace('auto oldPath = home + u"/.TelegramDesktop/"_q;', 'auto oldPath = home + u"/.CloudTalk-legacy/"_q;')
    lx.save()
    la = Src(up, S + 'core/launcher.cpp')
    la.replace('QApplication::setApplicationName(u"TelegramDesktop"_q);', 'QApplication::setApplicationName(u"CloudTalk"_q);')
    la.save()
    mw = Src(up, S + 'window/main_window.cpp')
    mw.replace('setTitle((user.isEmpty() ? u"Telegram"_q : user) + added + suffix);',
               'setTitle((user.isEmpty() ? Tgx::GetBrand().name : user) + added + suffix);')
    mw.replace('#include "window/main_window.h"\n', '#include "window/main_window.h"\n\n#include "tgx/tgx.h"\n')
    for name in ('logo_256.png', 'logo_256_no_margin.png'):
        mw.replace(f'QImage(u":/gui/art/{name}"_q);',
                   f'QImage(Tgx::IsVice() ? u":/gui/art/{name.replace("logo", "tgx_vice")}"_q : u":/gui/art/{name}"_q);')
    mw.save()
    qrc = Src(up, 'Telegram/Resources/qrc/telegram/telegram.qrc')
    qrc.replace('    <file alias="art/logo_256.png">../../art/logo_256.png</file>\n',
                '    <file alias="art/logo_256.png">../../art/logo_256.png</file>\n'
                '    <file alias="art/tgx_vice_256.png">../../art/tgx_vice_256.png</file>\n'
                '    <file alias="art/tgx_vice_256_no_margin.png">../../art/tgx_vice_256_no_margin.png</file>\n')
    qrc.save()
    # the user's data is always in AppData: upstream keeps it next to the exe when that folder is writable (an
    # administrator's Program Files), and the installer would remove it
    lg = Src(up, S + 'logs.cpp')
    lg.replace('#if (!defined Q_OS_WIN && !defined _DEBUG) || defined Q_OS_WINRT || defined OS_WIN_STORE || defined OS_MAC_STORE\n'
               '\t\tcForceWorkingDir(psAppDataPath());',
               '#if 1 // Cloud Talk\n\t\tcForceWorkingDir(psAppDataPath());')
    lg.save()
    # every visible «Telegram Desktop» / «Telegram» that stands for the program itself
    for rel, old in (('boxes/about_box.cpp', 'u"Telegram Desktop"_q'), ('intro/intro_start.cpp', 'u"Telegram Desktop"_q'),
                     ('settings/sections/settings_notifications.cpp', 'u"Telegram Desktop"_q'),
                     ('window/window_main_menu.cpp', 'u"Telegram Desktop"_q'),
                     ('window/notifications_manager_default.cpp', 'u"Telegram Desktop"_q'),
                     ('window/window_restore_shell.cpp', 'u"Telegram"_q'), ('core/crash_report_window.cpp', 'u"Telegram"_q'),
                     ('window/window_saved_windows.cpp', 'u"Telegram"_q')):
        f = Src(up, S + rel)
        f.replace(old, 'Tgx::GetBrand().name')
        head = f'#include "{rel[:-4]}.h"\n'
        f.replace(head, head + '\n#include "tgx/tgx.h"\n')
        if rel == 'intro/intro_start.cpp':   # the sign-in to our account is reachable before the sign-in to Telegram
            f.replace('	setDescriptionText(tr::lng_intro_about());\n', '''	setDescriptionText(tr::lng_intro_about());
	const auto bypass = Ui::CreateChild<Ui::LinkButton>(this, Tgx::BypassTitle());
	bypass->setClickedCallback([] { Tgx::AccountClicked(); });
	sizeValue() | rpl::on_next([=](QSize size) {
		bypass->moveToLeft((size.width() - bypass->width()) / 2, size.height() - 2 * bypass->height());
	}, bypass->lifetime());
''')
        if rel == 'boxes/about_box.cpp':
            f.replace('"https://github.com/telegramdesktop/tdesktop")),', '"https://github.com/dexterresurrection/vicetalk")),')
        if rel == 'window/window_main_menu.cpp':
            f.replace('u"https://desktop.telegram.org"_q', 'u"https://cabinet.zjd.uk/app/"_q')
        f.save()
    rv = Src(up, S + 'ui/controls/round_video_recorder.cpp')
    rv.replace('u"Telegram"_q.toUpper()', 'u"TALK"_q')
    rv.save()
    # first screen: our icon instead of the plane, the colours of the line
    st = Src(up, S + 'intro/intro_step.cpp')
    st.replace('#include "intro/intro_step.h"\n', '#include "intro/intro_step.h"\n\n#include "tgx/tgx.h"\n')
    st.replace('	st::introCoverIcon.paint(p, planeLeft, planeTop, width());',
               '	Tgx::PaintIntroLogo(p, planeLeft + st::introCoverIconLeft, planeTop, st::introCoverIcon.width());')
    st.replace('		auto color = anim::color(st::introCoverTopBg, st::introCoverBottomBg, y / realHeight);',
               '		auto color = Tgx::IsVice()\n'
               '			? anim::color(QColor(0x16, 0x0a, 0x3d), QColor(0xb8, 0x1f, 0x9b), y / realHeight)\n'
               '			: anim::color(st::introCoverTopBg, st::introCoverBottomBg, y / realHeight);')
    for side in ('	st::introCoverLeft.paint(p, left,', '	st::introCoverRight.paint(p, width() - right'):
        st.replace(side, '	if (!Tgx::IsVice()) ' + side.strip())
    st.save()
    qr = Src(up, S + 'intro/intro_qr.cpp')
    qr.replace('''		p.setBrush(QrActiveColor());
		p.setPen(Qt::NoPen);
		p.drawEllipse(QRect(QPoint(), size));
		st::introQrPlane.paintInCenter(p, QRect(QPoint(), size));
''', '''		p.drawImage(QRect(QPoint(), size), Window::LogoNoMargin());
''')
    qr.replace('#include "intro/intro_qr.h"\n', '#include "intro/intro_qr.h"\n\n#include "window/main_window.h"\n')
    qr.save()

    # ---- texts: never «official», the app is called by its brand name (cloud language packs included)
    li = Src(up, S + 'lang/lang_instance.cpp')
    li.replace('''	ParseKeyValue(key, value, [&](ushort key, QString &&value) {
		_nonDefaultSet[key] = 1;''', '''	ParseKeyValue(key, value, [&](ushort key, QString &&value) {
		if (key == tr::lng_intro_about.base) {
			value = Tgx::IntroAbout();
		} else if (key == tr::lng_about_text1.base) {
			if (value.contains(u"Официальн"_q)) {
				value.replace(u"Официальн"_q, u"Неофициальн"_q);
			} else if (value.contains(u"Official "_q)) {
				value.replace(u"Official "_q, u"Unofficial "_q);
			} else {
				return; // our own English text
			}
		} else {
			value.replace(u"Telegram Desktop"_q, Tgx::GetBrand().name);
		}
		_nonDefaultSet[key] = 1;''')
    li.replace('#include "lang/lang_instance.h"\n', '#include "lang/lang_instance.h"\n\n#include "tgx/tgx.h"\n')
    li.save()
    ls = Src(up, 'Telegram/Resources/langs/lang.strings')
    ls.sub(r'"lng_intro_about" = "[^"]*";', '"lng_intro_about" = "Unofficial Telegram client by CloudVPN.\\\\nWith a built-in bypass of blocks.";')
    ls.replace('"lng_about_text1" = "Official free messaging app based on {api_link}', '"lng_about_text1" = "Unofficial messaging app based on {api_link}')

    # built-in English texts serve both lines: «the app» instead of a name (cloud packs get the brand name at run time)
    def neutral(m):
        t = m.group(2).replace('Your Telegram Desktop version', 'Your version of the app').replace('your unlocked Telegram Desktop', 'your unlocked app')
        t = re.sub(r'(^|\\\\n|[.!?]\s)Telegram Desktop', lambda k: k.group(1) + 'The app', t)
        return m.group(1) + t.replace('Telegram Desktop', 'the app')
    ls.text = re.sub(r'^("lng_[^"]*" = ")(.*)$', neutral, ls.text, flags=re.M)
    ls.save()

    # ---- start, connection state
    ap = Src(up, S + 'core/application.cpp')
    ap.replace('	refreshGlobalProxy(); // Depends on app settings being read.\n',
               '	refreshGlobalProxy(); // Depends on app settings being read.\n	Tgx::Start(); // bypass + updates (Cloud Talk)\n')
    ap.replace('''		int32 state) {
	_private->proxyRotation->handleConnectionStateChanged(account, state);''', '''		int32 state) {
	if (account == &activeAccount()) {
		Tgx::ConnectionStateChanged(state);
	}
	_private->proxyRotation->handleConnectionStateChanged(account, state);''')
    ap.replace('#include "core/application.h"\n', '#include "core/application.h"\n\n#include "tgx/tgx.h"\n')
    ap.save()

    # ---- settings → advanced → connection: bypass mode and our apps
    sa = Src(up, S + 'settings/sections/settings_advanced.cpp')
    sa.replace('''	button->addClickHandler([=] {
		controller->show(ProxiesBoxController::CreateOwningBox(account));
	});
}''', '''	button->addClickHandler([=] {
		controller->show(ProxiesBoxController::CreateOwningBox(account));
	});
	const auto bypass = AddButtonWithLabel(
		container,
		rpl::single(Tgx::BypassTitle()),
		Tgx::BypassStatusValue(),
		st::settingsButton,
		{ &st::menuIconNetwork });
	bypass->addClickHandler([=] {
		controller->show(Box([](not_null<Ui::GenericBox*> box) {
			const auto options = Tgx::BypassModeNames();
			SingleChoiceBox(box, {
				.title = rpl::single(Tgx::BypassTitle()),
				.options = options,
				.initialSelection = int(Tgx::GetBypassMode()),
				.callback = [](int index) {
					Tgx::SetBypassMode(Tgx::BypassMode(index));
				},
			});
		}));
	});
	const auto acct = AddButtonWithLabel(
		container,
		rpl::single(Tgx::AccountTitle()),
		Tgx::AccountStatusValue(),
		st::settingsButton,
		{ &st::menuIconNetwork });
	acct->addClickHandler([] { Tgx::AccountClicked(); });
	const auto apps = AddButtonWithLabel(
		container,
		rpl::single(Tgx::AppsTitle()),
		rpl::single(QString()),
		st::settingsButton,
		{ &st::menuIconNetwork });
	apps->addClickHandler([] { Tgx::OpenApps(); });
}''')
    sa.replace('#include "settings/sections/settings_advanced.h"\n', '#include "settings/sections/settings_advanced.h"\n\n#include "tgx/tgx.h"\n')
    sa.save()

    # ---- build files
    cm = Src(up, 'Telegram/CMakeLists.txt')
    cm.replace('    config.h\n', '    config.h\n    tgx/tgx.cpp\n    tgx/tgx.h\n    tgx/tgx_signin.cpp\n    tgx/tgx_signin.h\n')
    # static Qt6Network: QDnsLookup needs the system's DNS library
    cm.text += '\nif (WIN32)\n    target_link_libraries(Telegram PRIVATE dnsapi)\nendif()\n'
    cm.save()
    rc = Src(up, 'Telegram/Resources/winrc/Telegram.rc')
    rc.replace('VALUE "CompanyName", "Telegram FZ-LLC"', 'VALUE "CompanyName", "CloudVPN"')
    rc.replace('VALUE "FileDescription", "Telegram Desktop"', 'VALUE "FileDescription", "Cloud Talk (unofficial Telegram client)"')
    rc.replace('VALUE "ProductName", "Telegram Desktop"', 'VALUE "ProductName", "Cloud Talk"')
    rc.save()

    # ---- overlay: our sources and icons over Telegram's logo files
    ov = os.path.join(HERE, 'overlay')
    for dp, _, files in os.walk(ov):
        for f in files:
            src = os.path.join(dp, f)
            dst = os.path.join(up, os.path.relpath(src, ov))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
    print('applied')


if __name__ == '__main__':
    main()
