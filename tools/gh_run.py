#!/usr/bin/env python3
"""Run a workflow of the public repository github.com/dexterresurrection/vicetalk (workflow_dispatch on main)
and fetch its artifacts:   tools/gh_run.py <workflow.yml> <out dir> [--wait-min 600] [--input key=value …]
Publish the tree first (tools/publish_public.sh). Token: /root/chihua.txt, only in the Authorization header."""
import datetime
import io
import os
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ci_run  # noqa: E402

ci_run.REPO = 'dexterresurrection/vicetalk'
api = ci_run.api


def main():
    a = sys.argv[1:]
    if len(a) < 2:
        sys.exit(__doc__)
    wf, out = a[0], a[1]
    wait_min = int(a[a.index('--wait-min') + 1]) if '--wait-min' in a else 600
    inputs = dict(x.split('=', 1) for x in [a[i + 1] for i, v in enumerate(a) if v == '--input'])
    since = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=5)
    body = {'ref': 'main'}
    if inputs:
        body['inputs'] = inputs
    api(f'/actions/workflows/{wf}/dispatches', method='POST', body=body)
    print('dispatched', wf, flush=True)
    run, last = None, ''
    deadline = time.time() + wait_min * 60
    while time.time() < deadline:
        time.sleep(60)
        runs = api(f'/actions/workflows/{wf}/runs?event=workflow_dispatch&per_page=5')['workflow_runs']
        run = next((r for r in runs if datetime.datetime.fromisoformat(r['created_at'].replace('Z', '+00:00')) >= since), None)
        if not run:
            continue
        jobs = api(f"/actions/runs/{run['id']}/jobs?per_page=50")['jobs']
        st = ' | '.join(f"{j['name']}:{j['status'][:4]}/{(j['conclusion'] or '-')[:4]}" for j in jobs)
        if st != last:
            print(time.strftime('%H:%M'), 'run', run['id'], st, flush=True)
            last = st
        if run['status'] == 'completed':
            break
    if not run:
        print('no run appeared')
        return 2
    print('run', run['id'], run['status'], run['conclusion'], run['html_url'], flush=True)
    os.makedirs(out, exist_ok=True)
    for job in api(f"/actions/runs/{run['id']}/jobs?per_page=50")['jobs']:
        if job['conclusion'] not in ('success', 'skipped'):
            try:
                open(os.path.join(out, f"log-{job['name'].replace('/', '_').replace(' ', '_')}.txt"), 'wb').write(
                    api(f"/actions/jobs/{job['id']}/logs", raw=True))
            except Exception as e:  # noqa: BLE001
                print('log of', job['name'], 'not available:', e)
    for art in api(f"/actions/runs/{run['id']}/artifacts?per_page=100")['artifacts']:
        if art['name'] == 'libs' or art['name'].startswith(('libs-', 'native-')):
            print('artifact', art['name'], art['size_in_bytes'] // 1048576, 'MB stays on GitHub (reused by run id)')
            continue
        zipfile.ZipFile(io.BytesIO(api(art['archive_download_url'], raw=True))).extractall(os.path.join(out, art['name']))
        print('artifact', art['name'], '→', os.path.join(out, art['name']), flush=True)
    return 0 if run['conclusion'] == 'success' else 1


if __name__ == '__main__':
    sys.exit(main())
