#!/bin/bash
# Publish the current tree (HEAD) to the public repository github.com/dexterresurrection/vicetalk as one snapshot commit.
#   tools/publish_public.sh "message"
# Public rules: author «Chihua Browser» <dexterresurrection@users.noreply.github.com>, no Co-Authored-By lines,
# the token only goes through git's credential helper (/root/chihua.txt), no keys / signing / secrets in the tree
# (keystore/ and /root/vicetalk-secrets are outside git; the script refuses if a key file shows up).
set -euo pipefail
SRC=$(cd "$(dirname "$0")/.." && pwd)
PUB=${PUB:-/root/vicetalk-public}
REPO=https://github.com/dexterresurrection/vicetalk.git
HELPER='!f() { echo username=x-access-token; echo "password=$(cat /root/chihua.txt)"; }; f'
MSG=${1:-"Update"}
if git -C "$SRC" ls-files | grep -E '\.(jks|keystore|p12|pem)$|keystore/|secrets' ; then echo "refusing: key-like files are tracked"; exit 1; fi
if [ ! -d "$PUB/.git" ]; then
  git init -q -b main "$PUB"
  git -C "$PUB" remote add origin "$REPO"
  git -C "$PUB" -c credential.helper="$HELPER" fetch -q origin main 2>/dev/null && git -C "$PUB" reset -q --soft origin/main || true
fi
find "$PUB" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
git -C "$SRC" archive HEAD | tar -x -C "$PUB"
git -C "$PUB" add -A
if git -C "$PUB" diff --cached --quiet; then echo "nothing to publish"; exit 0; fi
git -C "$PUB" -c user.name="Chihua Browser" -c user.email=dexterresurrection@users.noreply.github.com commit -q -m "$MSG"
git -C "$PUB" -c credential.helper="$HELPER" push -q origin HEAD:main
echo "published $(git -C "$PUB" rev-parse --short HEAD) ← $(git -C "$SRC" rev-parse --short HEAD)"
