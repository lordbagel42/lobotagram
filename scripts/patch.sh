#!/usr/bin/env bash
# Patch an Instagram APK with the lobotagram patch bundle.
#
#   scripts/patch.sh <instagram.apk|.apkm|.xapk|.apks> [-o out.apk] [--install]
#                    [--trace] [-- <extra revanced-cli args>]
#
# Split bundles are merged with APKEditor first, because a patcher works on one
# APK. The patched APK is signed with ./lobotagram.keystore, generated on the
# first run and MUST be kept: Android only accepts an update signed with the
# same key, so losing it means uninstalling and losing app data.

set -euo pipefail

CLI_VERSION="6.0.0"
CLI_JAR_NAME="revanced-cli-${CLI_VERSION}-all.jar"
CLI_URL="https://github.com/ReVanced/revanced-cli/releases/download/v${CLI_VERSION}/${CLI_JAR_NAME}"
CLI_SHA256="c25549bc17d59d2eb94fa5f86e60e9b77a02772ca88f7050f8f1276f923a9958"

APKEDITOR_VERSION="1.4.9"
APKEDITOR_JAR_NAME="APKEditor-${APKEDITOR_VERSION}.jar"
APKEDITOR_URL="https://github.com/REAndroid/APKEditor/releases/download/V${APKEDITOR_VERSION}/${APKEDITOR_JAR_NAME}"
APKEDITOR_SHA256="a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67"

KEYSTORE_ALIAS="lobotagram"
# Not a secret: the keystore file itself is the secret, and ReVanced CLI needs
# the password on the command line anyway.
KEYSTORE_PASSWORD="lobotagram"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/tools"
RVP="$ROOT/build/lobotagram.rvp"
KEYSTORE="$ROOT/lobotagram.keystore"

usage() {
    # The header comment above is the help text.
    awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit "${1:-1}"
}

die() {
    echo "error: $*" >&2
    exit 1
}

# download <url> <destination> <sha256>
download() {
    local url="$1" destination="$2" expected="$3"
    echo "==> Downloading $url"
    mkdir -p "$(dirname "$destination")"
    curl --fail --location --progress-bar --output "$destination.part" "$url" ||
        die "failed to download $url"

    local actual
    actual="$(sha256sum "$destination.part" | cut -d' ' -f1)"
    if [ "$actual" != "$expected" ]; then
        rm -f "$destination.part"
        die "checksum mismatch for $(basename "$destination"): expected $expected, got $actual"
    fi
    mv "$destination.part" "$destination"
}

input=""
output=""
install=0
trace=0
# Everything after a bare -- is handed to ReVanced CLI, e.g. -e "<patch name>".
passthrough=()

while [ $# -gt 0 ]; do
    case "$1" in
        -h | --help) usage 0 ;;
        --)
            shift
            passthrough=("$@")
            break
            ;;
        -o | --out)
            [ $# -ge 2 ] || die "$1 needs a path"
            output="$2"
            shift 2
            ;;
        -i | --install)
            install=1
            shift
            ;;
        --trace)
            trace=1
            shift
            ;;
        -*) die "unknown option $1 (see --help)" ;;
        *)
            [ -z "$input" ] || die "only one input file is supported"
            input="$1"
            shift
            ;;
    esac
done

[ -n "$input" ] || usage
[ -f "$input" ] || die "no such file: $input"

# Toolchain.
CLI_JAR="${LOBOTAGRAM_CLI_JAR:-$TOOLS/$CLI_JAR_NAME}"
[ -f "$CLI_JAR" ] || download "$CLI_URL" "$CLI_JAR" "$CLI_SHA256"

# Patch bundle.
if [ ! -f "$RVP" ]; then
    echo "==> $RVP is missing, building it"
    (cd "$ROOT" && ./gradlew build)
fi
[ -f "$RVP" ] || die "the build did not produce $RVP"

# Split bundles have to be merged into one APK before patching.
case "$input" in
    *.apkm | *.xapk | *.apks | *.zip)
        APKEDITOR_JAR="${LOBOTAGRAM_APKEDITOR_JAR:-$TOOLS/$APKEDITOR_JAR_NAME}"
        [ -f "$APKEDITOR_JAR" ] || download "$APKEDITOR_URL" "$APKEDITOR_JAR" "$APKEDITOR_SHA256"

        merged="$ROOT/build/merged/$(basename "${input%.*}").apk"
        mkdir -p "$(dirname "$merged")"
        echo "==> Merging splits with APKEditor into $merged"
        java -jar "$APKEDITOR_JAR" m -f -i "$input" -o "$merged"
        input="$merged"
        ;;
esac

if [ -z "$output" ]; then
    output="$ROOT/build/$(basename "${input%.*}")-lobotagram.apk"
fi
mkdir -p "$(dirname "$output")"

if [ ! -f "$KEYSTORE" ]; then
    # ReVanced signs with a BouncyCastle keystore (KeyStore.getInstance("BKS",
    # "BC")), which keytool can write using the BouncyCastle provider bundled
    # in the CLI fat jar.
    echo "==> Creating $KEYSTORE"
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storetype BKS \
        -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider \
        -providerpath "$CLI_JAR" \
        -alias "$KEYSTORE_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=lobotagram" \
        -storepass "$KEYSTORE_PASSWORD" \
        -keypass "$KEYSTORE_PASSWORD"
    echo "    Keep this file and back it up. Android only accepts an update"
    echo "    signed with the same key, so losing it costs you your app data."
fi

log="$ROOT/build/patch.log"
mkdir -p "$(dirname "$log")"

cli_args=(
    patch
    # -b bypasses signature and provenance verification of the bundle, which we
    # do not sign.
    -b
    -p "$RVP"
    -o "$output"
    --keystore "$KEYSTORE"
    --keystore-password "$KEYSTORE_PASSWORD"
    --keystore-entry-alias "$KEYSTORE_ALIAS"
    --keystore-entry-password "$KEYSTORE_PASSWORD"
    --signer "$KEYSTORE_ALIAS"
    -t "$ROOT/build/patch-tmp"
    --purge
)
if [ "$install" -eq 1 ]; then cli_args+=(-i); fi
if [ "${#passthrough[@]}" -gt 0 ]; then cli_args+=("${passthrough[@]}"); fi
cli_args+=("$input")

echo "==> Patching $(basename "$input")"
set +e
# Patching a 135 MB APK holds every dex file in memory; 6 GB is enough headroom.
java ${LOBOTAGRAM_JAVA_OPTS:--Xmx6g} -jar "$CLI_JAR" "${cli_args[@]}" 2>&1 | tee "$log"
status="${PIPESTATUS[0]}"
set -e

echo
echo "==> Patch results"
# The CLI logs one line per patch: '"<name>" succeeded', 'failed' or 'disabled'.
if ! grep -E '" (succeeded|failed|disabled)' "$log" | sed -E 's/^[A-Z]+: /  /'; then
    echo "  (the bundle applied no patches; patches that are off by default need"
    echo "   -- -e \"<patch name>\", see $log)"
fi

[ "$status" -eq 0 ] || die "patching failed, see $log"

echo
echo "==> Wrote $output"
echo "    Signed with $KEYSTORE (alias $KEYSTORE_ALIAS). Keep this file."

if [ "$trace" -eq 1 ]; then
    if [ "$install" -eq 1 ] && command -v adb > /dev/null; then
        echo "==> Enabling trace mode on the connected device"
        adb shell setprop log.tag.Lobotagram DEBUG
    fi
    echo "    Trace mode:  adb shell setprop log.tag.Lobotagram DEBUG"
    echo "    Read it:     adb logcat -s Lobotagram"
fi
