#!/bin/bash
# In the emulator (runner with KVM; Telegram is blocked on the host by prepare-check.sh):
#  A. Cloud APK, mode «auto»: starts, Telegram does not connect directly, the bypass turns on by itself, the client
#     connects through the xray core; the test server's access log shows the Telegram data centers.
#  B. Vice APK over it (same key): launcher entry is the Vice alias with the Vice name; starts and connects.
# Result: results/api-<N>/summary.txt, screenshots, logcat. Any FAIL → exit code 1.
set -uo pipefail
ADB=$(command -v adb)
adb() { timeout 300 "$ADB" "$@"; }
R=results/api-${API:-x}
mkdir -p "$R"
SUM=$R/summary.txt
cp results/host.txt "$SUM" 2>/dev/null || : > "$SUM"
FAILS=$(grep -c '^FAIL' "$SUM" || true)
ok()   { echo "OK   $*" | tee -a "$SUM"; }
bad()  { echo "FAIL $*" | tee -a "$SUM"; FAILS=$((FAILS+1)); }
note() { echo "--   $*" | tee -a "$SUM"; }
shot() { timeout 30 adb exec-out screencap -p > "$R/$1.png" 2>/dev/null || true; }
PKG=uk.zjd.cloudtalk
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell settings put secure anr_show_background 0 >/dev/null 2>&1
note "Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r')), ABI $(adb shell getprop ro.product.cpu.abilist | tr -d '\r')"

wait_log() {   # wait_log <regex> <seconds>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    adb logcat -d -s tgx:I 2>/dev/null | grep -E -m1 "$1" && return 0
    sleep 3
  done
  return 1
}


ui_dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml 2>/dev/null; }
dismiss_system() {   # "Pixel Launcher isn't responding" of a slow runner covers our window
  if ui_dump | grep -q "isn't responding"; then
    tap_xy "Wait" ; echo "--   system ANR window dismissed" | tee -a "$SUM"
  fi
}
tap_xy() {
  local xy
  xy=$(ui_dump | python3 -c '
import re, sys
x, want = sys.stdin.read(), sys.argv[1]
for m in re.finditer(r"<node [^>]*>", x):
    n = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", n); b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if t and b and t.group(1) == want:
        x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1")
  [ -n "$xy" ] && adb shell input tap $xy
}
tap_text() {   # tap_text <regex> [seconds]: waits for a node whose text matches (case-insensitive) and taps its centre
  local end=$((SECONDS + ${2:-20})) xy
  while [ $SECONDS -lt $end ]; do
    dismiss_system
    xy=$(ui_dump | python3 -c '
import re, sys
x, want = sys.stdin.read(), re.compile(sys.argv[1], re.I)
for m in re.finditer(r"<node [^>]*>", x):
    n = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", n)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if t and b and want.fullmatch(t.group(1).strip()):
        x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1")
    if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi
    sleep 2
  done
  return 1
}

update_check() {   # C. in-app update: old build 0.0.9 → the newer build from the runner's downloads.json
  local newver; newver=$(cat VERSION)
  adb uninstall $PKG >/dev/null 2>&1
  if adb install ci/old.apk >/dev/null 2>&1; then ok "update: old build 0.0.9 installed"; else bad "update: install old build"; return; fi
  adb logcat -c
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  if l=$(wait_log "update: mine 0\.0\.9, latest $newver" 90); then ok "update: the app sees $newver in downloads.json"; else bad "update: no update check"; return; fi
  if tap_text "(update|обновить)" 30; then ok "update: «Update» tapped in the update dialog"; else bad "update: no update dialog"; shot update-dialog; return; fi
  if wait_log "update: downloaded" 90 >/dev/null; then ok "update: APK downloaded inside the app, sha256 checked"; else bad "update: download"; return; fi
  if wait_log "asked for the install permission" 8 >/dev/null; then
    shot update-permission
    if tap_text "(allow from this source|разрешить установку из этого источника)" 20; then ok "update: install permission given in Settings"; else
      adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow; note "update: permission switch not found, appops used"; fi
  fi
  if wait_log "update: installer opened" 40 >/dev/null; then ok "update: system installer opened by the app"; else bad "update: installer not opened"; return; fi
  sleep 3; shot update-installer
  tap_text "(update|install|обновить|установить)" 30 || note "update: installer button not found"
  local end=$((SECONDS + 90)) v=''
  while [ $SECONDS -lt $end ]; do
    v=$(adb shell dumpsys package $PKG | tr -d '\r' | grep -m1 -o "versionName=[^ ]*")
    case "$v" in *-ct.$newver) break ;; esac
    sleep 3
  done
  case "$v" in *-ct.$newver) ok "update: in-app update 0.0.9 → $newver done ($v)" ;; *) bad "update: still $v after the installer"; shot update-after ;; esac
}

run_line() {   # run_line <apk> <tag> <expected alias> <expected label>
  local apk=$1 tag=$2 alias=$3 label=$4
  if adb install -r "$apk" >/dev/null 2>&1; then ok "$tag: installed"; else bad "$tag: install"; return; fi
  local la
  la=$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $PKG 2>/dev/null | tr -d '\r' | grep -o "$PKG/[A-Za-z0-9_.]*" | head -3 | tr '\n' ' ')
  if echo "$la" | grep -q "$alias"; then ok "$tag: launcher entry $alias"; else bad "$tag: launcher entry is '$la', want $alias"; fi
  adb shell pm clear $PKG >/dev/null 2>&1   # a fresh start: «direct first», not the bypass remembered by an earlier run
  adb logcat -c
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  shot "$tag-start"
  local lbl
  lbl=$(adb shell dumpsys package $PKG | tr -d '\r' | grep -m1 -o "versionName=[^ ]*")
  note "$tag: $lbl"
  if wait_log "telegram state [0-9]+ \(direct\)" 40 >/dev/null; then ok "$tag: client started, direct attempt first (mode auto)"; else bad "$tag: no tgx state log"; fi
  if l=$(wait_log "enable: auto" 60); then ok "$tag: bypass turned on by itself: ${l#*: }"; else bad "$tag: bypass did not turn on"; fi
  if l=$(wait_log "xray up on" 30); then ok "$tag: xray core running (${l##*xray up on })"; else bad "$tag: xray core did not start"; fi
  if l=$(wait_log "telegram state 3 \(bypass\)" 90); then ok "$tag: Telegram connected through the bypass"; else bad "$tag: Telegram did not connect through the bypass"; fi
  if adb shell ps -A 2>/dev/null | grep -q libxray; then ok "$tag: libxray process present"; else bad "$tag: no libxray process"; fi
  sleep 5
  shot "$tag-connected"
  adb shell "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > "$R/$tag-ui.xml" 2>/dev/null || true
  if grep -q "$label" "$R/$tag-ui.xml"; then ok "$tag: «$label» on screen"; else note "$tag: «$label» not on the first screen (see $tag-connected.png)"; fi
  if grep -q '>Telegram<\|text="Telegram"' "$R/$tag-ui.xml"; then bad "$tag: the word Telegram as a title on the first screen"; fi
  adb logcat -d > "$R/$tag-logcat.txt" 2>/dev/null
  if grep -q "FATAL EXCEPTION" "$R/$tag-logcat.txt"; then bad "$tag: crash (FATAL EXCEPTION in logcat)"; grep -A25 -m1 "FATAL EXCEPTION" "$R/$tag-logcat.txt" | tee -a "$SUM"; fi
  grep " tgx " "$R/$tag-logcat.txt" | tail -40 > "$R/$tag-tgx.txt" || true
}

update_check
run_line ci/cloud.apk cloud DefaultIcon "Cloud Talk"
n=$(sudo grep -cE '(149\.154\.|91\.108\.)[0-9.]+:(443|80|5222)' /opt/xr/access.log 2>/dev/null || echo 0)
if [ "${n:-0}" -gt 0 ]; then ok "test server: $n connections to Telegram data centers went through it"; else bad "test server: no Telegram connections in its log"; fi
sudo tail -5 /opt/xr/access.log | sed 's/^/     /' | tee -a "$SUM"
run_line ci/vice.apk vice ViceIcon "Vice Talk"
sudo cp /opt/xr/access.log "$R/server-access.log" 2>/dev/null; sudo chmod 644 "$R/server-access.log" 2>/dev/null
echo "FAILS=$FAILS" | tee -a "$SUM"
[ "$FAILS" -eq 0 ]
