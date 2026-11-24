#!/usr/bin/env python3
"""The cabinet's sign-in endpoints for the CI checks of shared sign-in (no real service, accounts or tokens):
    fake_cabinet.py <port> <talk-profile.json> <log>"""
import json
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, PROFILE, LOG = int(sys.argv[1]), sys.argv[2], sys.argv[3]
codes, refresh, access, lock = {}, {}, set(), threading.Lock()   # code → {token, ok}


def note(s):
    with lock, open(LOG, 'a') as f:
        f.write(time.strftime('%H:%M:%S ') + s + '\n')


def session(first=True):
    a, r = 'a-' + secrets.token_hex(8), 'r-' + secrets.token_hex(8)
    access.add(a)
    refresh[r] = True
    # a short life: the next request has to refresh
    out = {'access_token': a, 'refresh_token': r, 'expires_in': 30}
    if first:
        out['user'] = {'first_name': 'CI Anna'}
    return out


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def out(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        bearer = self.headers.get('Authorization', '')[7:]
        if self.path == '/api/cabinet/app/talk-profile':
            ok = bearer in access
            note(f'talk-profile {"ok" if ok else "401"}')
            return self.out(200, json.load(open(PROFILE))) if ok else self.out(401, {'detail': 'x'})
        if self.path == '/pending':   # for the check: codes not confirmed yet
            return self.out(200, [c for c, v in codes.items() if not v['ok']])
        self.out(404, {})

    def do_POST(self):
        n = int(self.headers.get('Content-Length') or 0)
        d = json.loads(self.rfile.read(n) or b'{}')
        p = self.path
        if p == '/api/cabinet/auth/device/request':
            code = '-'.join(secrets.token_hex(2).upper() for _ in range(2))
            token = secrets.token_hex(16)
            codes[code] = {'token': token, 'ok': False}
            note('device/request')
            return self.out(200, {'token': token, 'user_code': code, 'verification_url': f'http://10.0.2.2:{PORT}/device', 'telegram_url': '', 'expires_in': 600, 'interval': 2})
        if p == '/api/cabinet/auth/device/approve':
            code = d.get('user_code')
            who = self.headers.get('Authorization', '')
            ok = code in codes and who == 'Bearer donor'
            if ok:
                codes[code]['ok'] = True
            note(f'device/approve {"ok" if ok else "refused"}')
            return self.out(200 if ok else 404, {})
        if p == '/api/cabinet/auth/deeplink/poll':
            for c, v in list(codes.items()):
                if v['token'] == d.get('token'):
                    if not v['ok']:
                        return self.out(202, {})
                    del codes[c]
                    note('poll: signed in')
                    return self.out(200, session())
            return self.out(410, {})
        if p == '/api/cabinet/auth/refresh':
            ok = refresh.pop(d.get('refresh_token'), None)
            note(f'refresh {"ok" if ok else "401"} rotate={d.get("rotate")}')
            return self.out(200, session(False)) if ok else self.out(401, {})
        self.out(404, {})


ThreadingHTTPServer(('0.0.0.0', PORT), H).serve_forever()
