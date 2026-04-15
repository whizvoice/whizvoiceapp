#!/usr/bin/env bash
#
# Setup script for creating an Android emulator AVD snapshot on a DigitalOcean
# dedicated-CPU droplet with KVM. Designed to be scp'd to the droplet and run.
#
# Prerequisites:
#   - DigitalOcean dedicated-CPU droplet (e.g. c-4, 4 vCPU / 16 GB, Ubuntu 24.04)
#   - /dev/kvm must be available (dedicated CPU, not shared)
#
# Usage:
#   scp scripts/setup-snapshot-droplet.sh root@<ip>:~/
#   ssh root@<ip>
#   bash setup-snapshot-droplet.sh
#
set -euo pipefail

AVD_NAME="whiz-test-device"
SNAPSHOT_NAME="baseline_clean"
SYSTEM_IMAGE="system-images;android-36;google_apis_playstore;x86_64"
ANDROID_SDK_ROOT="/opt/android-sdk"
NOVNC_PORT=6080
VNC_PORT=5900
DISPLAY_NUM=1

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$PATH"

# ---------------------------------------------------------------------------
# Step 1: Verify KVM
# ---------------------------------------------------------------------------
echo "==> Checking for /dev/kvm..."
if [[ ! -e /dev/kvm ]]; then
    echo "ERROR: /dev/kvm not found."
    echo "This script requires a dedicated-CPU droplet (not shared CPU)."
    exit 1
fi
echo "    /dev/kvm is available."

# ---------------------------------------------------------------------------
# Step 2: Install system dependencies
# ---------------------------------------------------------------------------
echo "==> Installing system dependencies..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
    openjdk-21-jdk \
    unzip \
    wget \
    zstd \
    qemu-utils \
    xvfb \
    x11vnc \
    novnc \
    websockify \
    gh \
    git \
    pulseaudio

# ---------------------------------------------------------------------------
# Step 3: Install Android SDK
# ---------------------------------------------------------------------------
echo "==> Installing Android SDK..."
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

if [[ ! -d "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]]; then
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    echo "    Downloading command-line tools..."
    wget -q "$CMDLINE_TOOLS_URL" -O /tmp/cmdline-tools.zip
    unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-tmp
    mv /tmp/cmdline-tools-tmp/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-tmp
fi

echo "    Accepting licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "    Installing SDK packages (this may take a while)..."
sdkmanager --install \
    "platform-tools" \
    "emulator" \
    "$SYSTEM_IMAGE"

# ---------------------------------------------------------------------------
# Step 4: Create AVD
# ---------------------------------------------------------------------------
echo "==> Creating AVD: $AVD_NAME"
# Always create fresh to ensure clean QCOW2 files with relative backing paths
if [[ -d "$HOME/.android/avd/${AVD_NAME}.avd" ]]; then
    echo "    Deleting existing AVD for clean start..."
    rm -rf "$HOME/.android/avd/${AVD_NAME}.avd"
    rm -f "$HOME/.android/avd/${AVD_NAME}.ini"
fi
echo "no" | avdmanager create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "pixel_6" \
    --force

# Disable cache partition BEFORE first boot. The Android emulator's modified
# QEMU requires ALL block devices to have the snapshot tag during loadvm.
# The cache device always gets a fresh in-memory overlay without the tag,
# causing "Device 'cache' does not have the requested snapshot" on CI.
# By disabling it before AVD creation, the snapshot won't include cache state.
AVD_DIR_SETUP="$HOME/.android/avd/${AVD_NAME}.avd"
echo "==> Disabling cache partition (prevents snapshot loading issues on CI)..."
sed -i '/^disk.cachePartition/d' "$AVD_DIR_SETUP/config.ini"
echo "disk.cachePartition = no" >> "$AVD_DIR_SETUP/config.ini"
rm -f "$AVD_DIR_SETUP/cache.img" "$AVD_DIR_SETUP/cache.img.qcow2"
echo "    Set disk.cachePartition = no, removed cache.img files"

# ---------------------------------------------------------------------------
# Step 4b: Generate adb keys (prevents "unauthorized" on first boot)
# ---------------------------------------------------------------------------
echo "==> Generating adb keys..."
adb start-server
adb kill-server
# Ensure adb keys exist at ~/.android/adbkey
if [[ ! -f "$HOME/.android/adbkey" ]]; then
    echo "Warning: adb keys not generated. You may need to accept USB debugging dialog."
fi

# ---------------------------------------------------------------------------
# Step 5: Start Xvfb + VNC + noVNC
# ---------------------------------------------------------------------------
echo "==> Starting virtual display and VNC..."

# Kill any existing instances
pkill -f "Xvfb :${DISPLAY_NUM}" 2>/dev/null || true
pkill -f x11vnc 2>/dev/null || true
pkill -f websockify 2>/dev/null || true
sleep 1

# Start Xvfb
Xvfb ":${DISPLAY_NUM}" -screen 0 1280x800x24 &
XVFB_PID=$!
sleep 1

# Start x11vnc
x11vnc -display ":${DISPLAY_NUM}" -forever -nopw -rfbport "$VNC_PORT" -bg -o /tmp/x11vnc.log
sleep 1

# Start noVNC via websockify
websockify --web=/usr/share/novnc/ "$NOVNC_PORT" "localhost:${VNC_PORT}" &
WEBSOCKIFY_PID=$!
sleep 1

# Get the droplet's public IP
PUBLIC_IP=$(curl -s http://169.254.169.254/metadata/v1/interfaces/public/0/ipv4/address 2>/dev/null || hostname -I | awk '{print $1}')

echo ""
echo "============================================================"
echo "  noVNC is running!"
echo "  Open in your browser: http://${PUBLIC_IP}:${NOVNC_PORT}/vnc.html"
echo "============================================================"
echo ""

# ---------------------------------------------------------------------------
# Step 6: Boot the emulator (swangle for interactive setup — WhatsApp crashes swiftshader)
# ---------------------------------------------------------------------------
pkill -f qemu-system 2>/dev/null || true
sleep 2

echo "==> Booting emulator with swangle GPU (for interactive app setup)..."
DISPLAY=":${DISPLAY_NUM}" emulator -avd "$AVD_NAME" \
    -gpu swangle \
    -no-audio \
    -no-boot-anim \
    -memory 8192 \
    -cores 4 \
    -skin 1080x2400 \
    -no-snapshot-save \
    &
EMU_PID=$!

echo "    Waiting for emulator to boot..."
adb wait-for-device
# Wait for boot_completed
while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
    sleep 2
done
echo "    Emulator is booted!"

# Start logcat capture in background
echo "==> Starting logcat capture to /tmp/emulator-logcat.log..."
adb logcat > /tmp/emulator-logcat.log 2>&1 &
LOGCAT_PID=$!
echo "    Logcat PID: $LOGCAT_PID"

echo ""
echo "============================================================"
echo "  Emulator is ready (swangle GPU for interactive setup)!"
echo ""
echo "  1. Open http://${PUBLIC_IP}:${NOVNC_PORT}/vnc.html"
echo "  2. Do your interactive setup (install apps, log in, etc.)"
echo "  3. Come back here and press Enter to restart with CI settings"
echo "     and save the snapshot."
echo ""
echo "  Logcat: /tmp/emulator-logcat.log"
echo "  If emulator crashes, check:"
echo "    grep -i 'FATAL\|crash\|oom\|lowmemory\|kill' /tmp/emulator-logcat.log | tail -30"
echo "============================================================"
echo ""
read -r -p "Press Enter when you're done with interactive setup..."

# ---------------------------------------------------------------------------
# Step 7: Restart emulator with CI-matching settings and save snapshot
# ---------------------------------------------------------------------------
# CI uses: swiftshader_indirect, 2 cores, 4096MB RAM
# The snapshot must be saved with these exact settings for -snapshot loading to work.
echo "==> Killing interactive emulator..."
kill "$LOGCAT_PID" 2>/dev/null || true
adb emu kill 2>/dev/null || true
sleep 5
kill "$EMU_PID" 2>/dev/null || true
sleep 3

echo "==> Restarting emulator with CI-matching settings..."
echo "    GPU: swiftshader_indirect, cores: 2, memory: 4096MB"
DISPLAY=":${DISPLAY_NUM}" emulator -avd "$AVD_NAME" \
    -gpu swiftshader_indirect \
    -no-audio \
    -no-boot-anim \
    -memory 4096 \
    -cores 2 \
    -skin 1080x2400 \
    &
EMU_PID=$!

echo "    Waiting for emulator to boot..."
adb wait-for-device
while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
    sleep 2
done
echo "    Emulator booted with CI settings!"

# Disable animations
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 2147483647

echo "==> Saving snapshot: $SNAPSHOT_NAME"
adb emu avd snapshot save "$SNAPSHOT_NAME"
sleep 3

echo "==> Killing emulator..."
kill "$EMU_PID" 2>/dev/null || true
wait "$EMU_PID" 2>/dev/null || true
sleep 2

# ---------------------------------------------------------------------------
# Step 8: Verify snapshot
# ---------------------------------------------------------------------------
echo "==> Verifying snapshot..."
AVD_DIR="$HOME/.android/avd/${AVD_NAME}.avd"

# Check snapshot directory exists
if [[ -d "$AVD_DIR/snapshots/$SNAPSHOT_NAME" ]]; then
    echo "    Snapshot directory exists: $AVD_DIR/snapshots/$SNAPSHOT_NAME"
    ls -la "$AVD_DIR/snapshots/$SNAPSHOT_NAME/"
else
    echo "ERROR: Snapshot directory not found!"
    exit 1
fi

# Check qcow2 info (backing file, size, snapshots)
echo ""
echo "==> QCOW2 info:"
for qcow2 in "$AVD_DIR"/*.qcow2; do
    if [[ -f "$qcow2" ]]; then
        echo "    $(basename "$qcow2") ($(du -h "$qcow2" | cut -f1)):"
        qemu-img info "$qcow2" 2>/dev/null | grep -E "backing|format|virtual|disk" | sed 's/^/      /'
        qemu-img snapshot -l "$qcow2" 2>/dev/null | grep -E "ID|baseline" | sed 's/^/      /' || echo "      (no snapshots)"
    fi
done
echo ""
echo "==> IMPORTANT: The QCOW2 overlay must be a thin overlay (1-3GB)."
echo "    If userdata-qemu.img.qcow2 is larger than 4GB, something went wrong."
echo "    Delete all .qcow2 files and start fresh."

# ---------------------------------------------------------------------------
# Step 9: Upload
# ---------------------------------------------------------------------------
CURRENT_EMULATOR_BUILD=$(emulator -version 2>/dev/null | grep -oE 'build_id [0-9]+' | awk '{print $2}' || echo "")

echo ""
echo "============================================================"
echo "  Snapshot saved and verified!"
echo ""
echo "  Next steps to upload:"
echo "  1. Authenticate with GitHub:"
echo "       gh auth login"
echo "  2. Clone the repo:"
echo "       git clone https://github.com/whizvoice/whizvoiceapp.git"
echo "       cd whizvoiceapp"
echo "  3. Run the upload script (pin the emulator build so CI can match it):"
if [[ -n "$CURRENT_EMULATOR_BUILD" ]]; then
    echo "       ./scripts/avd-snapshot-upload.sh --version x86_64-v<N> --emulator-build ${CURRENT_EMULATOR_BUILD}"
else
    echo "       ./scripts/avd-snapshot-upload.sh --version x86_64-v<N> --emulator-build <build_id from 'emulator -version'>"
fi
echo "============================================================"
echo ""

# ---------------------------------------------------------------------------
# Cleanup VNC (optional — droplet will be destroyed anyway)
# ---------------------------------------------------------------------------
echo "==> Stopping logcat, VNC/noVNC..."
kill "$LOGCAT_PID" 2>/dev/null || true
kill "$WEBSOCKIFY_PID" 2>/dev/null || true
pkill -f x11vnc 2>/dev/null || true
kill "$XVFB_PID" 2>/dev/null || true

echo "Done!"
