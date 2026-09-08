#!/usr/bin/env bash
# Build the release APK and publish it as a GitHub release the launcher can find.
#
# The tablet polls  releases/latest/download/update.json  which never changes address,
# so all this has to do is make sure the newest release carries a correct update.json.
#
#   ./tools/publish-release.sh            # build + publish whatever version is in build.gradle.kts
#   ./tools/publish-release.sh --dry-run  # build + write update.json, publish nothing
set -euo pipefail

REPO="donovanw645/CarLauncher"
cd "$(dirname "$0")/.."

DRY=0
[[ "${1:-}" == "--dry-run" ]] && DRY=1

GH="$(command -v gh || echo "/c/Program Files/GitHub CLI/gh.exe")"
[[ -x "$GH" ]] || { echo "gh CLI not found. Install it, then: gh auth login"; exit 1; }

VNAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
VCODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts)
TAG="v$VNAME"
echo "==> Publishing $TAG (versionCode $VCODE)"

# Refuse to ship a version that is already out there - the tablet compares versionCode,
# and reusing one means nobody ever sees the update.
if [[ $DRY -eq 0 ]] && "$GH" release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "!! $TAG already exists. Bump versionCode/versionName in app/build.gradle.kts first."
  exit 1
fi

echo "==> Building"
./gradlew --quiet :app:assembleRelease

APK=$(ls -t "${LOCALAPPDATA}/CarLauncherBuild/app/outputs/apk/release/"*.apk | head -1)
[[ -f "$APK" ]] || { echo "no APK produced"; exit 1; }

OUT="dist"; mkdir -p "$OUT"
NAMED="$OUT/CarLauncher-$VNAME.apk"
cp -f "$APK" "$NAMED"

SHA=$(sha256sum "$NAMED" | cut -d' ' -f1)
SIZE=$(stat -c %s "$NAMED")
NOTES="${RELEASE_NOTES:-Update to $VNAME.}"

cat > "$OUT/update.json" <<JSON
{
  "versionCode": $VCODE,
  "versionName": "$VNAME",
  "apkUrl": "https://github.com/$REPO/releases/download/$TAG/CarLauncher-$VNAME.apk",
  "sha256": "$SHA",
  "sizeBytes": $SIZE,
  "minSdk": 24,
  "notes": "$NOTES"
}
JSON

echo "==> $NAMED  ($((SIZE/1024/1024)) MB)"
echo "==> sha256 $SHA"

if [[ $DRY -eq 1 ]]; then
  echo "==> dry run, nothing published. Manifest at $OUT/update.json"
  exit 0
fi

echo "==> Uploading release"
"$GH" release create "$TAG" "$NAMED" "$OUT/update.json" \
  --repo "$REPO" --title "$VNAME" --notes "$NOTES"

echo "==> Done. Tablet will offer $VNAME on its next check."
