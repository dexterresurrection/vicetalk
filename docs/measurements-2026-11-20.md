# Telegram from Russia: direct vs MTProto proxy vs xray (2026-11-20)

Vantage point: ho-rus-1000-4, 85.209.135.222 (Russian hosting, our relay node).
Tools: TCP probes (Python), TDLib 1.8.70 (`tdjson`) for MTProto connections, curl for HTTP.
xray: temporary VLESS + Reality (xtls-rprx-vision, uTLS chrome) server on innon (94.249.203.3:7777, Germany),
client on ho-rus with a local SOCKS5 — the same scheme the apps use.

## TCP to Telegram data centers (443, 80, 5222; 2 tries each)

| target | direct | via xray |
|---|---|---|
| DC1 149.154.175.50 | 0 / 6 | 6 / 6 |
| DC2 149.154.167.51 | 2 / 6 (5222 only) | 6 / 6 |
| DC3 149.154.175.100 | 0 / 6 | 6 / 6 |
| DC4 149.154.167.91 | 1 / 6 (5222 only) | 6 / 6 |
| DC5 91.108.56.130 | 0 / 6 | 6 / 6 |
| test DC2 149.154.167.40 | 1 / 6 | 6 / 6 |
| telegram.org web, CDN, updates | 0 / 18 | 18 / 18 |

ICMP ping to the DCs passes (47–142 ms): SYN packets are dropped, i.e. a block, not just throttling.

## MTProto (TDLib) to production DCs

| mode | connected | ping (3×) | testProxy DC1–DC5 |
|---|---|---|---|
| direct | no (60 s «Connecting») | timeout ×3 | — |
| xray (our VLESS Reality) | 2.55 s | 163 / 163 / 174 ms | 1.2–1.8 s |
| MTProxy ho-germ-1.zjd.uk | 2.7 s | 170–172 ms | 1.2–1.8 s |
| MTProxy p2pol12-1.zjd.uk | 3.0 s | 191–200 ms | 1.5–2.2 s |
| MTProxy ae-swe-2c.zjd.uk | 2.4 s | 187–192 ms | 1.5–2.4 s |
| MTProxy av3.zjd.uk | 3.7 s | 163–165 ms | 1.6–2.9 s |
| MTProxy nd-1-1000, p2fin-1, regusa1, play2fr8 (.zjd.uk) | no — port 9448 closed / no route from ho-rus | | |

19 of the 32 links the bot gives out in «TG Прокси» (/root/mtproto_proxy) still point at *.chocobo.fun, which no
longer resolves (domain retired 2026-10-29).

Test DCs: xray connects in 2.3–3.1 s; direct never; our MTProxies cannot reach test DCs (they only forward to
production DCs — expected). Login with test numbers 99966XYYYY was refused (PHONE_CODE_INVALID) with Telegram
Desktop's public development keys, so message/media round trips on test accounts were not measured.

## Throughput from Telegram servers (HTTP, 80–90 MB files)

| | direct | via xray |
|---|---|---|
| telegram.org/dl/desktop/linux (td.telegram.org) | 0 (no connection) | 20.5 – 27.7 MB/s |
| telegram.org/dl/android/apk (cdn4.telesco.pe) | 0 | 33.7 MB/s |
| t.me page | timeout 19.6 s | 0.31 s |

## Calls

Not measured end-to-end: a call needs two logged-in accounts. Telegram for Android 13.2 passes no proxy to private
calls at all (VoIPService → `Instance.makeInstance(…, null, …)`); Cloud Talk passes its local SOCKS5 there while the
bypass is on (TCP relays through the tunnel). Telegram Desktop: «use proxy for calls» is set with the bypass.

## Conclusion for the apps

- In this network Telegram does not work directly at all; both our xray tunnel and 4 of our MTProto proxies work.
- xray is the primary bypass (also carries calls and is not tied to Telegram's MTProxy protocol, which RKN can
  fingerprint); our MTProto proxies are the built-in fallback in the proxy list with Telegram's own rotation.

## The client itself from Russia (2026-11-21)

The Linux build of Cloud Talk desktop (same patch as Windows, CI run 44802321626) under Xvfb on ho-rus, test
bypass server on innon, Telegram Desktop development keys (demo only). Log: `demo-ho-rus-2026-11-21.log`.

- mode «off» (direct only): 80 s of «connecting» (state 0/1), one connection at 02:20:02 that dropped at once;
- mode «auto»: 8 s without a direct connection → bypass on → xray up → **connected (state 2) 3 s later**;
  the xray log shows the Telegram data centers (149.154.175.100, 149.154.167.51, 95.161.76.100, IPv6 DCs) through
  the tunnel. The demo also found a bug that is fixed now: the desktop client read brand.json/talk.json before
  QApplication existed, so the keys were not picked up from the executable's folder (15cdb35).
