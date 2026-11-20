# Cloud Talk / Vice Talk

**Unofficial** Telegram clients by CloudVPN for Android and Windows, with a built-in bypass of network blocks.
They use the Telegram API and are part of the Telegram ecosystem; they are not affiliated with Telegram.

- Android: [Telegram for Android](https://github.com/TelegramMessenger/Telegram-Android) (GPL-2.0-or-later) + `android/`
- Windows: [Telegram Desktop](https://github.com/telegramdesktop/tdesktop) (GPL-3.0 with the OpenSSL exception) + `desktop/`

This repository holds only our changes, applied to a pinned upstream revision by a script; the CI workflows show
the exact upstream commit/tag, how the sources are fetched and how the binaries are built. Together with the
upstream sources this is the complete corresponding source of the published builds.

## What is different from the upstream apps

- **Bypass.** When Telegram does not connect directly (8 s in «Connecting…»), the app starts an embedded
  [Xray](https://github.com/XTLS/Xray-core) core (MPL-2.0, unmodified release binary, separate process) with a
  local SOCKS5 proxy protected by a random login, and points Telegram's own proxy support at it — messages, files
  and calls of this app only, no system VPN. The choice is remembered per network type for 6 hours; the direct path
  is re-checked every 30 minutes. Our MTProto proxies are added to the proxy list as a fallback. Modes: automatic,
  always, off (Settings → «Обход блокировок» / «Bypass»; on Windows: Settings → Advanced → Connection).
  A proxy the user set up himself is never touched.
- **Names and logos.** «Cloud Talk» / «Vice Talk», own icons; Telegram's logo and the word «Telegram» as the app name
  are removed (Telegram API terms 2.3–2.4). The intro says that this is an unofficial client on the Telegram API.
- **Keys.** Our own `api_id`; it is not in this repository — release builds get it from `brand.json` / `talk.json`
  at packaging time. No fallback to Telegram's official keys.
- **Updates** from our `downloads.json` (the store/Telegram updaters and crash reports to Telegram are off).
- **«Vice apps» / «Our apps»** page in Settings with a download list of our other apps and an advertising plate.
  Ads are shown only there — never in the chat list or in chats. Telegram's sponsored messages in channels are
  untouched (Telegram API terms 3.3).
- Two lines from one build: the Vice APK is the Cloud APK with another `assets/brand.json` and launcher alias,
  signed with the same key; on Windows the line comes from the installer (`brand.json` next to the exe).

## Build

- Android: `.github/workflows/tgx-android.yml` — native libraries from the pinned sources
  (`TMessagesProj/jni/prebuild/build_all.sh` + CMake, per ABI), then `android/apply.py` and Gradle; emulator check
  with Telegram blocked on the runner. Release signing: `tools/release-android.sh`.
- Windows: `.github/workflows/tgx-desktop-win.yml` — Telegram Desktop libraries (`Telegram/build/prepare`),
  `desktop/apply.py`, CMake Release x64; installer `desktop/installer/cloudtalk.nsi` (NSIS, both lines).
- Linux (compile check): `.github/workflows/tgx-desktop-linux.yml`.

## Licences

Our changes are under the licence of the code they change: GPL-2.0-or-later for `android/`, GPL-3.0 with the
OpenSSL exception for `desktop/`. Xray-core is MPL-2.0. «Telegram» is a trademark of Telegram FZ-LLC.
