#!/usr/bin/env bash
#
# Download and restore the whiz-test-device AVD snapshot from GitHub Releases.
#
# Usage: ./scripts/avd-snapshot-download.sh [--force] [--release-tag TAG] [--system-image IMAGE] [--target-api LEVEL]
#
set -euo pipefail

# Resolve script directory early (before any cd)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

EMULATOR_BUILD=$(python3 -c "
import json
m = json.load(open('$DOWNLOAD_DIR/avd-snapshot-manifest.json'))
print(m.get('emulator_build', ''))
")

SNAPSHOT_SDK_ROOT=$(python3 -c "
import json
m = json.load(open('$DOWNLOAD_DIR/avd-snapshot-manifest.json'))
print(m.get('snapshot_sdk_root', ''))
")

SNAPSHOT_HOME=$(python3 -c "
import json
m = json.load(open('$DOWNLOAD_DIR/avd-snapshot-manifest.json'))
print(m.get('snapshot_home', ''))
")

echo "    Expected SHA-256: $EXPECTED_SHA256"
echo "    Parts: $PARTS"
if [[ -n "$EMULATOR_BUILD" ]]; then
    echo "    Emulator build: $EMULATOR_BUILD"
    echo "    NOTE: Pin emulator-build: $EMULATOR_BUILD in your CI workflow to match this snapshot."
fi

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
# Verify QCOW2 internal snapshots after extraction
# ---------------------------------------------------------------------------
echo "==> Verifying QCOW2 internal snapshots after extraction..."
QEMU_IMG=""
for candidate in "${SDK_ROOT}/emulator/qemu-img" "qemu-img"; do
    if command -v "$candidate" &>/dev/null || [[ -x "$candidate" ]]; then
        QEMU_IMG="$candidate"
        break
    fi
done
if [[ -z "$QEMU_IMG" ]]; then
    echo "    Warning: qemu-img not found, skipping QCOW2 snapshot verification."
else
    for qcow2 in "$AVD_DIR"/cache.img.qcow2 "$AVD_DIR"/userdata-qemu.img.qcow2 "$AVD_DIR"/encryptionkey.img.qcow2; do
        if [[ -f "$qcow2" ]]; then
            echo "    $(basename "$qcow2"):"
            "$QEMU_IMG" snapshot -l "$qcow2" 2>&1 | grep -E "(ID|baseline)" || echo "      NO SNAPSHOTS FOUND"
        fi
    done
fi
echo "==> AVD directory timestamps after extraction:"
ls -la "$AVD_DIR"/*.qcow2 "$AVD_DIR"/*.img 2>/dev/null || true

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

# Rewrite paths in snapshot.pb (protobuf binary with length-prefixed strings)
SNAPSHOT_PB="$AVD_DIR/snapshots/$SNAPSHOT_NAME/snapshot.pb"
if [[ -f "$SNAPSHOT_PB" ]]; then
    # Build replacement args: try manifest paths first, fall back to scanning the .pb
    PB_ARGS=()
    if [[ -n "$SNAPSHOT_SDK_ROOT" ]] && [[ "$SNAPSHOT_SDK_ROOT" != "$SDK_ROOT" ]]; then
        PB_ARGS+=("$SNAPSHOT_SDK_ROOT" "$SDK_ROOT")
    fi
    if [[ -n "$SNAPSHOT_HOME" ]] && [[ "$SNAPSHOT_HOME" != "$HOME" ]]; then
        PB_ARGS+=("$SNAPSHOT_HOME" "$HOME")
    fi

    # If manifest didn't have the paths, auto-detect from the .pb itself
    if [[ ${#PB_ARGS[@]} -eq 0 ]]; then
        DETECTED_HOME=$(strings "$SNAPSHOT_PB" | grep -oE '/[^ ]+/\.android/avd' | head -1 | sed 's|/.android/avd||')
        DETECTED_SDK=$(strings "$SNAPSHOT_PB" | grep -oE '/[^ ]+/system-images/' | head -1 | sed 's|/system-images/||')
        if [[ -n "$DETECTED_SDK" ]] && [[ "$DETECTED_SDK" != "$SDK_ROOT" ]]; then
            PB_ARGS+=("$DETECTED_SDK" "$SDK_ROOT")
        fi
        if [[ -n "$DETECTED_HOME" ]] && [[ "$DETECTED_HOME" != "$HOME" ]]; then
            PB_ARGS+=("$DETECTED_HOME" "$HOME")
        fi
    fi

    if [[ ${#PB_ARGS[@]} -gt 0 ]]; then
        echo "    Rewriting snapshot.pb paths..."
        python3 "$SCRIPT_DIR/rewrite_snapshot_paths.py" "$SNAPSHOT_PB" "${PB_ARGS[@]}"
    else
        echo "    snapshot.pb paths already match local environment."
    fi
fi

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
