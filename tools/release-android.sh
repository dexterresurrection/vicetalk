#!/bin/bash
# Release APKs from the CI build (talk-android artifact): Cloud and Vice, universal and arm64, our key.
#   tools/release-android.sh <CloudTalk-unsigned-ci.apk> <version> [out dir]
# Secrets stay outside the repository: /root/vicetalk-secrets/talk.json (chmod 600) =
#   {"api_id":…, "api_hash":"…", "bypass":{"mode":"auto","xray":[outbound…],"mtproto":[{"server","port","secret"}…],
#    "profiles_url":"…"}}
# Signing runs in the s3client-builder:2 image (Android SDK); wait for memory first, no build lock needed (light).
set -euo pipefail
IN=$1; VER=$2; OUT=${3:-/root/vicetalk/out/$VER}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SECRETS=${TALK_SECRETS:-/root/vicetalk-secrets/talk.json}
KS=$ROOT/keystore/keystore-talk.properties
[ -f "$SECRETS" ] || { echo "no $SECRETS (api_id/api_hash from my.telegram.org + bypass profiles)"; exit 1; }
mkdir -p "$OUT"
brand() {   # brand <cloud|vice> → brand.json text (secrets merged, never printed)
  python3 -I - "$1" "$SECRETS" "$VER" <<'PY'
import json, sys
line, secrets, ver = sys.argv[1], json.load(open(sys.argv[2])), sys.argv[3]
if not (isinstance(secrets.get('api_id'), int) and len(secrets.get('api_hash', '')) == 32):
    sys.exit('api_id/api_hash missing in the secrets file')
b = {'v': 1, 'brand': line, 'name': 'Vice Talk' if line == 'vice' else 'Cloud Talk',
     'product': 'vice-talk' if line == 'vice' else 'talk', 'theme': 'vice-night' if line == 'vice' else '',
     'version': ver, 'ads_slot': 'talk.settings', 'downloads': 'https://cabinet.zjd.uk/app/downloads.json'}
b.update({k: secrets[k] for k in ('api_id', 'api_hash', 'bypass') if k in secrets})
print(json.dumps(b, ensure_ascii=False, separators=(',', ':')))
PY
}
CLOUD=$(brand cloud); VICE=$(brand vice)
python3 "$ROOT/tools/brand_apk.py" --in "$IN" --out "$OUT/CloudTalk-$VER.apk" --keystore-props "$KS" --brand "$CLOUD" --any-cert
python3 "$ROOT/tools/brand_apk.py" --in "$IN" --out "$OUT/CloudTalk-arm64-$VER.apk" --keystore-props "$KS" --brand "$CLOUD" --any-cert --keep-abi arm64-v8a
python3 "$ROOT/tools/brand_apk.py" --in "$OUT/CloudTalk-$VER.apk" --out "$OUT/ViceTalk-$VER.apk" --keystore-props "$KS" --brand "$VICE" \
  --enable ViceIcon --disable DefaultIcon --label alias
python3 "$ROOT/tools/brand_apk.py" --in "$OUT/CloudTalk-arm64-$VER.apk" --out "$OUT/ViceTalk-arm64-$VER.apk" --keystore-props "$KS" --brand "$VICE" \
  --enable ViceIcon --disable DefaultIcon --label alias
ls -la "$OUT"
