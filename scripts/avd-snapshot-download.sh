#!/usr/bin/env bash
#
# Download and restore the whiz-test-device AVD snapshot from GitHub Releases.
#
# Usage: ./scripts/avd-snapshot-download.sh [--force] [--release-tag TAG] [--system-image IMAGE] [--target-api LEVEL]
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Constants — update these when uploading a new snapshot
# ---------------------------------------------------------------------------
SNAPSHOT_VERSION="v1"
RELEASE_TAG="emulator-snapshot-${SNAPSHOT_VERSION}"
REPO="whizvoice/whizvoiceapp"
AVD_NAME="whiz-test-device"
SNAPSHOT_NAME="baseline_clean"
SYSTEM_IMAGE="system-images;android-35;google_apis_playstore;arm64-v8a"

# ---------------------------------------------------------------------------
# Parse args
# ---------------------------------------------------------------------------
FORCE=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force) FORCE=true; shift ;;
        --release-tag) RELEASE_TAG="$2"; shift 2 ;;
        --system-image) SYSTEM_IMAGE="$2"; shift 2 ;;
        --target-api) TARGET_API="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
ANDROID_AVD_HOME="$HOME/.android/avd"
AVD_DIR="${ANDROID_AVD_HOME}/${AVD_NAME}.avd"
AVD_INI="${ANDROID_AVD_HOME}/${AVD_NAME}.ini"
SNAPSHOT_DIR="${AVD_DIR}/snapshots/${SNAPSHOT_NAME}"
DOWNLOAD_DIR=$(mktemp -d /tmp/avd-snapshot-download.XXXXXX)
trap 'echo "==> Cleaning up download dir"; rm -rf "$DOWNLOAD_DIR"' EXIT

# ---------------------------------------------------------------------------
# Check if already exists
# ---------------------------------------------------------------------------
if [[ -d "$SNAPSHOT_DIR" ]] && [[ "$FORCE" != "true" ]]; then
    echo "AVD snapshot already exists at $SNAPSHOT_DIR"
    echo "Use --force to re-download and overwrite."
    exit 0
fi

# ---------------------------------------------------------------------------
# Detect SDK root
# ---------------------------------------------------------------------------
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    SDK_ROOT="$ANDROID_SDK_ROOT"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
    SDK_ROOT="$ANDROID_HOME"
elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    SDK_ROOT="$HOME/Library/Android/sdk"
elif [[ -d "/opt/homebrew/share/android-commandlinetools" ]]; then
    SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
else
    echo "Error: Cannot detect Android SDK root."
    echo "Set ANDROID_SDK_ROOT or ANDROID_HOME environment variable."
    exit 1
fi
echo "==> Using SDK root: $SDK_ROOT"

# ---------------------------------------------------------------------------
# Validate prerequisites
# ---------------------------------------------------------------------------
for cmd in zstd gh shasum; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "Error: '$cmd' is required but not found in PATH"
        exit 1
    fi
done

# ---------------------------------------------------------------------------
# Install system image if needed
# ---------------------------------------------------------------------------
SYSTEM_IMAGE_DIR="${SDK_ROOT}/$(echo "$SYSTEM_IMAGE" | tr ';' '/')"
if [[ ! -d "$SYSTEM_IMAGE_DIR" ]]; then
    echo "==> Installing system image: $SYSTEM_IMAGE"
    SDKMANAGER=""
    for candidate in "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" \
                     "${SDK_ROOT}/tools/bin/sdkmanager" \
                     "sdkmanager"; do
        if command -v "$candidate" &>/dev/null || [[ -x "$candidate" ]]; then
            SDKMANAGER="$candidate"
            break
        fi
    done
    if [[ -z "$SDKMANAGER" ]]; then
        echo "Error: sdkmanager not found. Install Android SDK command-line tools."
        exit 1
    fi
    yes | "$SDKMANAGER" "$SYSTEM_IMAGE" || true
fi

# ---------------------------------------------------------------------------
# Download release assets
# ---------------------------------------------------------------------------
echo "==> Downloading snapshot from release $RELEASE_TAG..."
cd "$DOWNLOAD_DIR"

gh release download "$RELEASE_TAG" --repo "$REPO" --dir "$DOWNLOAD_DIR"

# ---------------------------------------------------------------------------
# Read manifest
# ---------------------------------------------------------------------------
if [[ ! -f "$DOWNLOAD_DIR/avd-snapshot-manifest.json" ]]; then
    echo "Error: manifest.json not found in release assets"
    exit 1
fi

# Parse manifest with python (available on macOS)
EXPECTED_SHA256=$(python3 -c "
import json, sys
m = json.load(open('$DOWNLOAD_DIR/avd-snapshot-manifest.json'))
print(m['sha256'])
")

PARTS=$(python3 -c "
import json
m = json.load(open('$DOWNLOAD_DIR/avd-snapshot-manifest.json'))
print(' '.join(m['parts']))
")

echo "    Expected SHA-256: $EXPECTED_SHA256"
echo "    Parts: $PARTS"

# ---------------------------------------------------------------------------
# Reassemble parts
# ---------------------------------------------------------------------------
ARCHIVE_PATH="$DOWNLOAD_DIR/avd-snapshot.tar.zst"
echo "==> Reassembling archive..."

# Concatenate parts in order
cat $DOWNLOAD_DIR/$PARTS > "$ARCHIVE_PATH"

# The above handles single-part (no glob needed) and multi-part cases
# because PARTS is space-separated and we use word splitting intentionally

ARCHIVE_SIZE=$(du -sh "$ARCHIVE_PATH" | cut -f1)
echo "    Archive size: $ARCHIVE_SIZE"

# ---------------------------------------------------------------------------
# Verify checksum
# ---------------------------------------------------------------------------
echo "==> Verifying SHA-256 checksum..."
ACTUAL_SHA256=$(shasum -a 256 "$ARCHIVE_PATH" | cut -d' ' -f1)

if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "Error: SHA-256 mismatch!"
    echo "    Expected: $EXPECTED_SHA256"
    echo "    Actual:   $ACTUAL_SHA256"
    exit 1
fi
echo "    Checksum verified."

# ---------------------------------------------------------------------------
# Extract
# ---------------------------------------------------------------------------
echo "==> Extracting to $AVD_DIR..."
mkdir -p "$AVD_DIR"

zstd -d "$ARCHIVE_PATH" --stdout | tar -xf - -C "$AVD_DIR"

# ---------------------------------------------------------------------------
# Rewrite absolute paths
# ---------------------------------------------------------------------------
echo "==> Rewriting absolute paths..."

SED_INPLACE=(sed -i)
if [[ "$(uname)" == "Darwin" ]]; then
    SED_INPLACE=(sed -i '')
fi

for ini_file in "$AVD_DIR/hardware-qemu.ini" \
                "$AVD_DIR/snapshots/$SNAPSHOT_NAME/hardware.ini"; do
    if [[ -f "$ini_file" ]]; then
        echo "    Rewriting $(basename "$ini_file")"
        "${SED_INPLACE[@]}" "s|__SDK_ROOT__|${SDK_ROOT}|g" "$ini_file"
        "${SED_INPLACE[@]}" "s|__HOME__|${HOME}|g" "$ini_file"
    fi
done

# ---------------------------------------------------------------------------
# Generate top-level AVD .ini
# ---------------------------------------------------------------------------
echo "==> Writing $AVD_INI"
mkdir -p "$(dirname "$AVD_INI")"
# Use TARGET_API if set, otherwise extract from SYSTEM_IMAGE (e.g. "system-images;android-35;..." -> 35)
if [[ -z "${TARGET_API:-}" ]]; then
    TARGET_API=$(echo "$SYSTEM_IMAGE" | sed 's/.*android-\([0-9]*\).*/\1/')
fi
cat > "$AVD_INI" <<EOF
avd.ini.encoding=UTF-8
path=${AVD_DIR}
path.rel=avd/${AVD_NAME}.avd
target=android-${TARGET_API}
EOF

# ---------------------------------------------------------------------------
# Create runtime directories (emulator expects these)
# ---------------------------------------------------------------------------
echo "==> Creating runtime directories..."
mkdir -p "$AVD_DIR/data/misc"
mkdir -p "$AVD_DIR/tmpAdbCmds"

# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------
echo "==> Verifying AVD registration..."

# Find emulator binary
EMULATOR_BIN=""
for candidate in "${SDK_ROOT}/emulator/emulator" \
                 "/opt/homebrew/share/android-commandlinetools/emulator/emulator"; do
    if [[ -x "$candidate" ]]; then
        EMULATOR_BIN="$candidate"
        break
    fi
done

if [[ -n "$EMULATOR_BIN" ]]; then
    if "$EMULATOR_BIN" -list-avds 2>/dev/null | grep -q "$AVD_NAME"; then
        echo "    AVD '$AVD_NAME' is registered and visible to the emulator."
    else
        echo "    Warning: AVD '$AVD_NAME' not found in emulator list."
        echo "    Available AVDs:"
        "$EMULATOR_BIN" -list-avds 2>/dev/null || true
    fi
else
    echo "    Warning: emulator binary not found, skipping verification."
fi

echo ""
echo "==> AVD snapshot restored successfully!"
echo "    AVD:      $AVD_NAME"
echo "    Snapshot:  $SNAPSHOT_NAME"
echo "    Location:  $AVD_DIR"
echo ""
echo "Boot with:"
echo "    emulator -avd $AVD_NAME -snapshot $SNAPSHOT_NAME"
