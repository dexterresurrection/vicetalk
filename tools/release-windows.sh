#!/bin/bash
# Windows installers from the CI build (artifact win64: CloudTalk-win64.zip): Cloud and Vice lines.
#   tools/release-windows.sh <CloudTalk-win64.zip> <version> [out dir]
# talk.json (api keys; no bypass profiles — they come from the CloudVPN account) is made here from
# /root/vicetalk-secrets/talk.json and is never in the repository. NSIS kit: desktop/installer (copy, kit-commit.txt).
set -euo pipefail
ZIP=$1; VER=$2
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${3:-$ROOT/out/$VER}
SECRETS=${TALK_SECRETS:-/root/vicetalk-secrets/talk.json}
[ -f "$SECRETS" ] || { echo "no $SECRETS"; exit 1; }
STAGE=$(mktemp -d -p "${TMPDIR:-/tmp}" talkwin.XXXXXX)
trap 'rm -rf "$STAGE"' EXIT
python3 -I -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$ZIP" "$STAGE"
[ -f "$STAGE/CloudTalk/CloudTalk.exe" ] || { echo "no CloudTalk.exe in $ZIP"; exit 1; }
umask 077
python3 -I - "$SECRETS" "$VER" > "$STAGE/CloudTalk/talk.json" <<'PY'
import json, sys
s, ver = json.load(open(sys.argv[1])), sys.argv[2]
if not (isinstance(s.get('api_id'), int) and len(s.get('api_hash', '')) == 32):
    sys.exit('api_id/api_hash missing in the secrets file')
print(json.dumps({'api_id': s['api_id'], 'api_hash': s['api_hash'], 'version': ver,
                  'downloads': 'https://cabinet.zjd.uk/app/downloads.json', 'bypass': {'mode': 'auto'}}, separators=(',', ':')))
PY
umask 022
mkdir -p "$OUT"
cd "$ROOT/desktop/installer"
makensis -V2 -DSTAGE="$STAGE/CloudTalk" -DVERSION="$VER" -DOUTFILE="$OUT/CloudTalk-Setup-$VER.exe" cloudtalk.nsi
makensis -V2 -DCI_BRAND=vice -DSTAGE="$STAGE/CloudTalk" -DVERSION="$VER" -DOUTFILE="$OUT/ViceTalk-Setup-$VER.exe" cloudtalk.nsi
ls -la "$OUT"/*Setup*
