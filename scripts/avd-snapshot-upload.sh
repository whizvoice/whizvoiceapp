#!/usr/bin/env bash
#
# Package and upload the whiz-test-device AVD snapshot to GitHub Releases.
#
# Usage: ./scripts/avd-snapshot-upload.sh [--version v1] [--emulator-build BUILD_NUMBER]
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
VERSION="v1"
EMULATOR_BUILD=""
REPO="whizvoice/whizvoiceapp"
AVD_NAME="whiz-test-device"
AVD_DIR="$HOME/.android/avd/${AVD_NAME}.avd"
SNAPSHOT_NAME="baseline_clean"
STAGING_DIR=""
SPLIT_PREFIX="/tmp/whiz-avd-snapshot.tar.zst.part-"
ARCHIVE_PATH="/tmp/avd-snapshot.tar.zst"

# ---------------------------------------------------------------------------
# Parse args
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version) VERSION="$2"; shift 2 ;;
        --emulator-build) EMULATOR_BUILD="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

RELEASE_TAG="emulator-snapshot-${VERSION}"

# Auto-detect emulator build number if not provided
if [[ -z "$EMULATOR_BUILD" ]]; then
    EMULATOR_BIN=""
    for candidate in "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/emulator/emulator" \
                     "/opt/homebrew/share/android-commandlinetools/emulator/emulator"; do
        if [[ -x "$candidate" ]]; then
            EMULATOR_BIN="$candidate"
            break
        fi
    done
    if [[ -n "$EMULATOR_BIN" ]]; then
        EMULATOR_BUILD=$("$EMULATOR_BIN" -version 2>/dev/null | grep -o 'build_id [0-9]*' | head -1 | awk '{print $2}' || true)
    fi
    if [[ -z "$EMULATOR_BUILD" ]]; then
        echo "Warning: Could not auto-detect emulator build number. Use --emulator-build to set it."
    else
        echo "==> Auto-detected emulator build: $EMULATOR_BUILD"
    fi
fi

# ---------------------------------------------------------------------------
# Validate prerequisites
# ---------------------------------------------------------------------------
for cmd in zstd gh tar split sha256sum; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "Error: '$cmd' is required but not found in PATH"
        exit 1
    fi
done

# Ensure emulator is not running (snapshot files can be corrupted if in use)
if command -v pgrep &>/dev/null; then
    if pgrep -f "qemu-system.*${AVD_NAME}" &>/dev/null; then
        echo "Error: Emulator appears to be running. Shut it down first."
        exit 1
    fi
fi

if [[ ! -d "$AVD_DIR" ]]; then
    echo "Error: AVD directory not found at $AVD_DIR"
    exit 1
fi

if [[ ! -d "$AVD_DIR/snapshots/$SNAPSHOT_NAME" ]]; then
    echo "Error: Snapshot '$SNAPSHOT_NAME' not found at $AVD_DIR/snapshots/$SNAPSHOT_NAME"
    exit 1
fi

echo "==> Packaging AVD snapshot (version=$VERSION)"

# ---------------------------------------------------------------------------
# Stage essential files
# ---------------------------------------------------------------------------
STAGING_DIR=$(mktemp -d /tmp/avd-snapshot-staging.XXXXXX)
trap 'echo "==> Cleaning up staging dir"; rm -rf "$STAGING_DIR" "$ARCHIVE_PATH" ${SPLIT_PREFIX}*' EXIT

echo "==> Staging files to $STAGING_DIR"

# Config files
for f in config.ini AVD.conf emulator-user.ini version_num.cache hardware-qemu.ini; do
    if [[ -f "$AVD_DIR/$f" ]]; then
        cp "$AVD_DIR/$f" "$STAGING_DIR/$f"
    fi
done

# Disk images (qcow2 + base .img files)
for f in userdata-qemu.img.qcow2 userdata-qemu.img \
         encryptionkey.img.qcow2 encryptionkey.img \
         cache.img.qcow2 cache.img; do
    if [[ -f "$AVD_DIR/$f" ]]; then
        echo "    Copying $f ($(du -sh "$AVD_DIR/$f" | cut -f1))"
        cp "$AVD_DIR/$f" "$STAGING_DIR/$f"
    fi
done

# Snapshot directory
echo "    Copying snapshots/$SNAPSHOT_NAME/"
mkdir -p "$STAGING_DIR/snapshots/$SNAPSHOT_NAME"
cp -r "$AVD_DIR/snapshots/$SNAPSHOT_NAME/"* "$STAGING_DIR/snapshots/$SNAPSHOT_NAME/"

# Modem simulator
if [[ -d "$AVD_DIR/modem_simulator" ]]; then
    echo "    Copying modem_simulator/"
    cp -r "$AVD_DIR/modem_simulator" "$STAGING_DIR/modem_simulator"
fi

# ---------------------------------------------------------------------------
# Sanitize absolute paths
# ---------------------------------------------------------------------------
echo "==> Sanitizing absolute paths"

# Detect SDK root from the hardware-qemu.ini itself
SDK_ROOT=$(grep '^android.sdk.root' "$STAGING_DIR/hardware-qemu.ini" | cut -d'=' -f2 | tr -d ' ')
if [[ -z "$SDK_ROOT" ]]; then
    echo "Warning: Could not detect SDK root from hardware-qemu.ini"
    SDK_ROOT="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
fi

for ini_file in "$STAGING_DIR/hardware-qemu.ini" \
                "$STAGING_DIR/snapshots/$SNAPSHOT_NAME/hardware.ini"; do
    if [[ -f "$ini_file" ]]; then
        echo "    Sanitizing $(basename "$ini_file")"
        # Replace SDK root first (longer path, to avoid partial replacement)
        sed -i "s|${SDK_ROOT}|__SDK_ROOT__|g" "$ini_file"
        # Replace home directory
        sed -i "s|${HOME}|__HOME__|g" "$ini_file"
    fi
done

# ---------------------------------------------------------------------------
# Compress
# ---------------------------------------------------------------------------
echo "==> Compressing with zstd (level 19, multithreaded)..."
tar -cf - -C "$STAGING_DIR" . | zstd -T0 -19 -o "$ARCHIVE_PATH"

ARCHIVE_SIZE=$(du -sh "$ARCHIVE_PATH" | cut -f1)
echo "    Archive size: $ARCHIVE_SIZE"

# ---------------------------------------------------------------------------
# Checksum
# ---------------------------------------------------------------------------
SHA256=$(sha256sum "$ARCHIVE_PATH" | cut -d' ' -f1)
echo "    SHA-256: $SHA256"

# ---------------------------------------------------------------------------
# Split into chunks (<2GB each for GitHub Releases)
# ---------------------------------------------------------------------------
echo "==> Splitting into 1900MB chunks..."
split -b 1900m "$ARCHIVE_PATH" "$SPLIT_PREFIX"

PARTS=()
for part in ${SPLIT_PREFIX}*; do
    part_name=$(basename "$part")
    part_size=$(du -sh "$part" | cut -f1)
    echo "    $part_name ($part_size)"
    PARTS+=("$part")
done

echo "    Total parts: ${#PARTS[@]}"

# ---------------------------------------------------------------------------
# Write manifest
# ---------------------------------------------------------------------------
MANIFEST_PATH="/tmp/avd-snapshot-manifest.json"

# Build part filenames JSON array
PART_FILES_JSON="["
first=true
for part in "${PARTS[@]}"; do
    if $first; then first=false; else PART_FILES_JSON+=","; fi
    PART_FILES_JSON+="\"$(basename "$part")\""
done
PART_FILES_JSON+="]"

EMULATOR_BUILD_JSON=""
if [[ -n "$EMULATOR_BUILD" ]]; then
    EMULATOR_BUILD_JSON="\"emulator_build\": \"${EMULATOR_BUILD}\","
fi

cat > "$MANIFEST_PATH" <<EOF
{
  "version": "${VERSION}",
  "arch": "x86_64",
  "api_level": 36,
  "system_image": "system-images;android-36;google_apis_playstore;x86_64",
  ${EMULATOR_BUILD_JSON}
  "avd_name": "${AVD_NAME}",
  "snapshot_name": "${SNAPSHOT_NAME}",
  "snapshot_sdk_root": "${SDK_ROOT}",
  "snapshot_home": "${HOME}",
  "parts": ${PART_FILES_JSON},
  "sha256": "${SHA256}",
  "created_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF

echo "==> Manifest:"
cat "$MANIFEST_PATH"

# ---------------------------------------------------------------------------
# Upload to GitHub Releases
# ---------------------------------------------------------------------------
echo ""
echo "==> Creating GitHub Release: $RELEASE_TAG"

# Delete existing release if it exists (to allow re-upload)
if gh release view "$RELEASE_TAG" --repo "$REPO" &>/dev/null; then
    echo "    Deleting existing release $RELEASE_TAG..."
    gh release delete "$RELEASE_TAG" --repo "$REPO" --yes
    # Clean up the tag if it exists (may not exist if previous upload failed mid-way)
    git push --delete origin "$RELEASE_TAG" 2>/dev/null || true
fi

# Build asset list
ASSETS=("$MANIFEST_PATH")
for part in "${PARTS[@]}"; do
    ASSETS+=("$part")
done

gh release create "$RELEASE_TAG" \
    --repo "$REPO" \
    --title "Emulator AVD Snapshot ${VERSION}" \
    --notes "AVD snapshot for \`${AVD_NAME}\` with \`${SNAPSHOT_NAME}\` snapshot.

**Arch:** x86_64
**API Level:** 36
**System Image:** google_apis_playstore;x86_64
**SHA-256:** \`${SHA256}\`
**Parts:** ${#PARTS[@]}

Download and restore with:
\`\`\`
./scripts/avd-snapshot-download.sh
\`\`\`" \
    "${ASSETS[@]}"

echo ""
echo "==> Upload complete!"
echo "    Release: $RELEASE_TAG"
echo "    Repo:    $REPO"
echo "    Parts:   ${#PARTS[@]}"
