# Shared sign-in (only the asking side), sourced by check-android.sh: test donors (signin/donor), a fake cabinet on the
# runner (signin/fake_cabinet.py), builds signin-cloud.apk / signin-vice.apk that trust the donors' CI certificate.
CAB=results/cabinet.log
cab() { grep -c "$1" "$CAB" 2>/dev/null || true; }
ui_has() { ui_dump | grep -qE "$1"; }
wait_ui() {   # wait_ui <regex> <seconds>
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do dismiss_system; ui_has "$1" && return 0; sleep 2; done
  return 1
}
start_app() { adb shell am force-stop $PKG; adb logcat -c; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
open_bypass() {   # the button of the first screen
  tap_text "(bypass|обход блокировок)" 40
}
signin_check() {
  adb uninstall $PKG >/dev/null 2>&1
  for p in uk.zjd.cloudvpn uk.zjd.cloudmail uk.zjd.vicewave uk.zjd.cloudsocial; do adb uninstall $p >/dev/null 2>&1; done
  # ---- no donors: the offer of the sign-in, the code, confirmed «in the cabinet»
  if adb install ci/signin-cloud.apk >/dev/null 2>&1; then ok "sign-in: Cloud test build installed"; else bad "sign-in: install"; return; fi
  start_app
  if wait_log "offer: sign in for the bypass" 60 >/dev/null; then ok "sign-in: Telegram blocked and no servers — the sign-in is offered"; else bad "sign-in: no offer"; fi
  sleep 1; shot signin-offer
  tap_text "(sign in|войти)" 20 || { tap_text "(not now|не сейчас)" 3; open_bypass; }
  if wait_log "sign-in: 0 app.s. can confirm" 20 >/dev/null; then ok "sign-in, no donors: nothing offered"; else bad "sign-in, no donors: unexpected"; fi
  if tap_text "sign in to cloudvpn with a code" 30; then :; else bad "sign-in: no code button"; shot signin-nocode; fi
  if wait_ui "Sign-in code: [0-9A-F]{4}-[0-9A-F]{4}" 20; then ok "sign-in, no donors: the device code is shown"; else bad "sign-in: no code on screen"; fi
  shot signin-code
  local code; code=$(ui_dump | grep -oE "Sign-in code: [0-9A-F]{4}-[0-9A-F]{4}" | head -1 | sed 's/.*: //')
  curl -s -o /dev/null -X POST -H 'Authorization: Bearer donor' -d "{\"user_code\":\"$code\"}" http://127.0.0.1:8081/api/cabinet/auth/device/approve
  if wait_log "sign-in: done" 30 >/dev/null; then ok "sign-in by the code: done"; else bad "sign-in by the code"; fi
  if wait_log "profiles: 1 xray" 20 >/dev/null; then ok "sign-in: bypass servers of the account arrived"; else bad "sign-in: no bypass servers"; fi
  if wait_log "telegram state 3 \(bypass\)" 90 >/dev/null; then ok "sign-in: Telegram connected through the account's bypass"; else bad "sign-in: no connection through the account's bypass"; fi
  tap_text "refresh bypass servers" 20; sleep 5
  if [ "$(cab 'refresh ok rotate=True')" -ge 1 ]; then ok "session renewed with rotate"; else bad "refresh without rotate"; fi
  tap_text "refresh bypass servers|servers refreshed" 20; sleep 5
  if [ "$(cab 'refresh ok rotate=True')" -ge 2 ] && [ "$(cab 'refresh 401')" -eq 0 ]; then ok "the rotated token is the one used next"; else bad "rotated token not used"; fi
  tap_text "sign out" 20; sleep 2
  # ---- donors: one of our account, an older one, another service, a fake with a wrong certificate
  for d in ci-vpn ci-old ci-wave ci-fake; do adb install ci/donors/$d.apk >/dev/null 2>&1 || bad "sign-in: install $d"; done
  start_app
  sleep 8; tap_text "(not now|не сейчас)" 6
  if open_bypass; then ok "sign-in: the bypass opens from the first screen"; else bad "sign-in: no «Bypass» on the first screen"; shot signin-intro; fi
  if wait_log "sign-in: 2 app.s. can confirm" 20 >/dev/null; then ok "donors: two of four are offered"; else bad "donors: $(adb logcat -d -s tgx:I | grep 'can confirm' | tail -1)"; fi
  if wait_ui "Continue as CI Anna — through “CI VPN”" 15; then ok "donors: «Continue as CI Anna — through CI VPN»"; else bad "donors: no button"; fi
  shot signin-donors
  if ui_has "CI Old"; then ok "donors: an app older than the field answers for CloudVPN"; else bad "donors: the older app is missing"; fi
  if ui_has "CI Fake|CI Mallory"; then bad "donors: wrong certificate accepted"; else ok "donors: wrong certificate — not offered"; fi
  if ui_has "CI Wave|CI Vera"; then bad "donors: another service accepted"; else ok "donors: an account of vice.zjd.uk — not offered to Cloud Talk"; fi
  if adb logcat -d -s donor:I | grep -q "uk.zjd.cloudsocial asked"; then bad "donors: the fake app was contacted"; else ok "donors: the fake app was not even contacted"; fi
  # ---- refusal
  local done0; done0=$(cab 'poll: signed in')
  tap_text "continue as ci anna — through “ci vpn”" 15
  if tap_text "deny" 25; then ok "refusal: the other app asked the person"; else bad "refusal: no window of the other app"; shot signin-deny; fi
  if wait_log "sign-in: not confirmed" 15 >/dev/null && wait_ui "The sign-in was not confirmed" 10; then ok "refusal: «The sign-in was not confirmed»"; else bad "refusal: no message"; fi
  shot signin-refused
  sleep 8
  if [ "$(cab 'poll: signed in')" = "$done0" ] && [ "$(cab 'device/approve ok')" -eq 1 ]; then ok "refusal: no session appeared"; else bad "refusal: a session appeared"; fi
  if adb logcat -d -s donor:I | grep -q "extras.*pub"; then bad "the request carries a key"; else ok "the request carries only the code and the service (no key is asked for)"; fi
  # ---- consent
  tap_text "continue as ci anna — through “ci vpn”" 20
  tap_text "allow" 25 || bad "consent: no window"
  if wait_log "sign-in: confirmed by another app" 15 >/dev/null && wait_log "sign-in: done" 20 >/dev/null; then ok "consent: signed in through the other app, no code typed"; else bad "consent: not signed in"; fi
  if wait_ui "CloudVPN account: CI Anna" 15; then ok "consent: the account is on screen"; else bad "consent: no account on screen"; fi
  shot signin-done
  adb logcat -d -v time > "$R/signin-logcat.txt" 2>/dev/null
  if grep -E " [IDWE]/tgx" "$R/signin-logcat.txt" | grep -qE "[0-9A-F]{4}-[0-9A-F]{4}|r-[0-9a-f]{16}|a-[0-9a-f]{16}"; then bad "log: a code or a token in the app's log"; else ok "log: no codes and no tokens"; fi
  # ---- the Vice line over it: another service, the CloudVPN session and servers are not used
  if adb install -r ci/signin-vice.apk >/dev/null 2>&1; then ok "sign-in: Vice test build over Cloud"; else bad "sign-in: Vice install"; return; fi
  start_app
  sleep 10; tap_text "(not now|не сейчас)" 8
  open_bypass
  if wait_log "sign-in: 1 app.s. can confirm" 20 >/dev/null && wait_ui "Continue as CI Vera — through “CI Wave”" 15; then ok "Vice: only the app with an account of vice.zjd.uk is offered"; else bad "Vice: $(adb logcat -d -s tgx:I | grep 'can confirm' | tail -1)"; fi
  if ui_has "Vice VPN account: |CI VPN|CI Old"; then bad "Vice: the CloudVPN account is used"; else ok "Vice: not signed in by the CloudVPN session"; fi
  if wait_ui "Bypass servers: 0" 10; then ok "Vice: the bypass servers of the other account are gone"; else bad "Vice: servers of the other account stayed"; fi
  shot signin-vice
  tap_text "continue as ci vera — through “ci wave”" 15
  tap_text "allow" 25
  if wait_log "sign-in: done" 25 >/dev/null; then ok "Vice: signed in through the Vice app"; else bad "Vice: not signed in"; fi
  adb logcat -d -s donor:I | grep donor | tail -20 > "$R/signin-donors.txt"
  cp "$CAB" "$R/cabinet.log" 2>/dev/null
}
signin_check
