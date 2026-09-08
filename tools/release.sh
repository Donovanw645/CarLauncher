#!/usr/bin/env bash
#
# Build, sign and publish a Car Launcher release, so the tablet can pick it up
# over the air.
#
#   tools/release.sh 1.2 "Short note about what changed"   bump, build, publish
#   tools/release.sh --same "Fixed the upload"             republish this version
#
# The tablet polls a single fixed address that never changes between releases:
#   https://github.com/OWNER/REPO/releases/latest/download/update.json
# and that manifest names the versioned APK for whichever release is newest.
#
set -euo pipefail

OWNER="donovanw645"
REPO="CarLauncher"

# The release signing certificate. Android only allows an in-place update when the
# new APK carries the same cert as the installed one - a debug-signed build would
# force an uninstall and wipe every setting and permission on the tablet. So the
# publish refuses to continue unless this matches exactly.
EXPECTED_CERT="73dca0cadde7a5779aa6724d4b5ccb043362c79bc1ebd27f6494bfb63dde74b6"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$ROOT/app/build.gradle.kts"
STAGE="$ROOT/build-release"

die() { echo "error: $*" >&2; exit 1; }

usage() {
    sed -n '3,13p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
    exit 1
}

[ $# -ge 1 ] || usage
command -v gh >/dev/null || die "the GitHub CLI is not installed - run: winget install --id GitHub.cli"
gh auth status >/dev/null 2>&1 || die "not signed in to GitHub - run: gh auth login"

CURRENT_CODE="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$GRADLE")"
CURRENT_NAME="$(sed -nE 's/^[[:space:]]*versionName = "([^"]*)".*/\1/p' "$GRADLE")"
[ -n "$CURRENT_CODE" ] || die "could not read versionCode from $GRADLE"

if [ "$1" = "--same" ]; then
    VCODE="$CURRENT_CODE"
    VNAME="$CURRENT_NAME"
    NOTES="${2:-}"
else
    VNAME="$1"
    NOTES="${2:-}"
    VCODE=$((CURRENT_CODE + 1))
    sed -i -E "s/^([[:space:]]*)versionCode = [0-9]+/\1versionCode = $VCODE/" "$GRADLE"
    sed -i -E "s/^([[:space:]]*)versionName = \"[^\"]*\"/\1versionName = \"$VNAME\"/" "$GRADLE"
    echo "==> version $CURRENT_NAME (build $CURRENT_CODE) -> $VNAME (build $VCODE)"
fi

TAG="v$VNAME"
APKNAME="CarLauncher-release-$VNAME-$VCODE.apk"

echo "==> building"
(cd "$ROOT" && ./gradlew assembleRelease --console=plain -q)

OUTDIR="$(cygpath -u "${LOCALAPPDATA}")/CarLauncherBuild/app/outputs/apk/release"
APK="$OUTDIR/$APKNAME"
[ -f "$APK" ] || die "expected APK not found: $APK"

# --- refuse to ship anything the tablet cannot install over the top of ----------
APKSIGNER="$(ls -1 /c/Android/sdk/build-tools/*/apksigner.bat 2>/dev/null | tail -1 || true)"
[ -n "$APKSIGNER" ] || die "apksigner not found under /c/Android/sdk/build-tools"
CERT="$("$APKSIGNER" verify --print-certs "$APK" | sed -nE 's/.*SHA-256 digest: ([0-9a-f]+).*/\1/p' | head -1)"
[ "$CERT" = "$EXPECTED_CERT" ] || die "APK is signed with $CERT, not the release key. Refusing to publish."
echo "==> signature ok"

SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$APK")"

mkdir -p "$STAGE"
MANIFEST="$STAGE/update.json"
OUTFILE="$MANIFEST" VCODE="$VCODE" VNAME="$VNAME" NOTES="$NOTES" SHA="$SHA" SIZE="$SIZE" \
APKURL="https://github.com/$OWNER/$REPO/releases/download/$TAG/$APKNAME" \
python -c '
import json, os
json.dump({
    "versionCode": int(os.environ["VCODE"]),
    "versionName": os.environ["VNAME"],
    "apkUrl":      os.environ["APKURL"],
    "sha256":      os.environ["SHA"],
    "sizeBytes":   int(os.environ["SIZE"]),
    "minSdk":      24,
    "notes":       os.environ["NOTES"],
}, open(os.environ["OUTFILE"], "w"), indent=2)
'
echo "==> manifest"
cat "$MANIFEST"

echo "==> committing"
(
    cd "$ROOT"
    git add -A
    git commit -q -m "Release $VNAME (build $VCODE)" -m "$NOTES" || echo "    (nothing to commit)"
    git push -q origin HEAD
)

echo "==> publishing $TAG"
gh release create "$TAG" "$APK" "$MANIFEST" \
    --repo "$OWNER/$REPO" \
    --title "Car Launcher $VNAME" \
    --notes "${NOTES:-Car Launcher $VNAME}"

echo
echo "Published. The tablet will offer $VNAME on its next check,"
echo "or immediately via Settings -> Software update -> Check now."
