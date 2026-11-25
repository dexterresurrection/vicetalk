#!/usr/bin/env python3
"""Check installers on a Windows runner (workflow tgx-win-check.yml of the vicetalk repo):
    tools/win_check.py [--workdir] <CloudTalk-Setup.exe> [ViceTalk-Setup.exe] <out dir>
Installers go to our bucket under a random ci/ key only for the run (deleted afterwards in any case)."""
import hashlib
import os
import secrets
import subprocess
import sys

sys.path.insert(0, '/root/scriptctl')
import publish_release as pr  # noqa: E402  (bucket client; keys stay in /root/s3.txt)

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    argv = [a for a in sys.argv[1:] if a != '--workdir']
    files, out = argv[:-1], argv[-1]
    if not files:
        sys.exit(__doc__)
    tag = secrets.token_hex(8)
    cli = pr.s3()
    keys, args = [], (['--input', 'workdir=1'] if '--workdir' in sys.argv else [])
    try:
        from boto3.s3.transfer import TransferConfig
        for i, f in enumerate(files):
            key = f'ci/{tag}/{os.path.basename(f)}'
            cli.upload_file(f, pr.BUCKET, key, ExtraArgs={'ContentType': 'application/octet-stream', 'ACL': 'public-read'},
                            Config=TransferConfig(multipart_threshold=64 << 20, multipart_chunksize=32 << 20, max_concurrency=2))
            keys.append(key)
            sha = hashlib.sha256(open(f, 'rb').read()).hexdigest()
            pre = 'setup' if i == 0 else 'vice'
            args += ['--input', f'{pre}_url={pr.BUCKET_URL}/{key}', '--input', f'{pre}_sha={sha}']
        print('installers in the bucket (temporary):', len(keys), flush=True)
        return subprocess.run([sys.executable, os.path.join(HERE, 'gh_run.py'), 'tgx-win-check.yml', out, '--wait-min', '60'] + args).returncode
    finally:
        for key in keys:
            try:
                cli.delete_object(Bucket=pr.BUCKET, Key=key)
            except Exception as e:  # noqa: BLE001
                print('could not delete', key, e)


if __name__ == '__main__':
    sys.exit(main())
