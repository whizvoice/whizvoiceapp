# Create x86_64 Emulator Snapshot for CI

## Goal

Create an Android emulator snapshot (x86_64, API 33) with WhatsApp and Fitbit pre-installed and logged in, then upload it to GitHub Releases so CI can use it for autofix verification tests.

## Prerequisites

- x86_64 Linux or Windows machine with KVM support (check: `ls /dev/kvm`)
- At least 4GB RAM free
- At least 20GB disk free
- A phone number for WhatsApp verification (it will send an SMS code)
- A Google/Fitbit account for Fitbit login
- `gh` CLI authenticated with access to `whizvoice/whizvoiceapp` repo
- `zstd` installed (`sudo apt install zstd` or equivalent)

## Step-by-Step Instructions

### 1. Install Android SDK and tools

If Android SDK is not installed:

```bash
# Download command-line tools from https://developer.android.com/studio#command-line-tools-only
# Or install via package manager if available

# Set ANDROID_HOME
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
```

Install required SDK components:

```bash
yes | sdkmanager "system-images;android-33;google_apis;x86_64" "platform-tools" "emulator"
```

### 2. Create the AVD

```bash
echo "no" | avdmanager create avd \
  --name whiz-test-device \
  --package "system-images;android-33;google_apis;x86_64" \
  --device "pixel_4" \
  --force
```

### 3. Download app APKs from GitHub release

```bash
mkdir -p /tmp/app-apks
gh release download app-apks-for-snapshot \
  --repo whizvoice/whizvoiceapp \
  --dir /tmp/app-apks
ls -la /tmp/app-apks/
```

This should download a WhatsApp `.apk` and a Fitbit `.apkm` file.

### 4. Boot the emulator WITH a display

You need a display so the user can manually log into apps.

If you have a graphical desktop:
```bash
emulator -avd whiz-test-device -gpu swiftshader_indirect -memory 4096
```

If headless (SSH), set up VNC or X11 forwarding first, then run the emulator. Alternatively:
```bash
# Install a VNC server and virtual framebuffer
sudo apt install tigervnc-standalone-server x11vnc xvfb
export DISPLAY=:1
Xvfb :1 -screen 0 1080x1920x24 &
x11vnc -display :1 -forever -nopw &
emulator -avd whiz-test-device -gpu swiftshader_indirect -memory 4096
```
Then VNC into the machine on port 5900 to see the emulator window.

### 5. Wait for boot and install apps

Wait for the emulator to fully boot:
```bash
adb wait-for-device
adb shell getprop sys.boot_completed  # should return "1"
```

Install WhatsApp (single APK):
```bash
adb install -r /tmp/app-apks/*.apk
```

Install Fitbit (APKM bundle — it's a zip of split APKs):
```bash
mkdir -p /tmp/fitbit_bundle
unzip /tmp/app-apks/*.apkm -d /tmp/fitbit_bundle
adb install-multiple -r /tmp/fitbit_bundle/*.apk
```

Verify both installed:
```bash
adb shell pm list packages | grep -E "(whatsapp|fitbit)"
```

Expected output:
```
package:com.whatsapp
package:com.fitbit.FitbitMobile
```

### 6. USER MANUAL STEP: Log into both apps

**This requires the user to interact with the emulator GUI.**

1. Open WhatsApp on the emulator, go through phone number verification (SMS code required)
2. Open Fitbit on the emulator, sign in with Google/Fitbit account
3. Make sure both apps reach their main/home screen after login

Disable animations while you're at it:
```bash
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 2147483647
```

### 7. Save the snapshot

```bash
adb emu avd snapshot save baseline_clean
```

Verify it was saved:
```bash
ls -la ~/.android/avd/whiz-test-device.avd/snapshots/baseline_clean/
```

You should see files like `ram.bin`, `textures.bin`, `hardware.ini`, `snapshot.pb`, etc.

### 8. Clone the repo and upload

```bash
git clone https://github.com/whizvoice/whizvoiceapp.git
cd whizvoiceapp
```

The upload script needs a small modification for x86_64. Run it with version `x86_64-v1`:

```bash
chmod +x scripts/avd-snapshot-upload.sh
```

Before running, edit `scripts/avd-snapshot-upload.sh` and change the manifest section near line 168-169 to use the correct arch/API:
- Change `"arch": "arm64-v8a"` to `"arch": "x86_64"`
- Change `"api_level": 35` to `"api_level": 33`
- Change `"system_image": "system-images;android-35;google_apis_playstore;arm64-v8a"` to `"system_image": "system-images;android-33;google_apis;x86_64"`

Then run:
```bash
./scripts/avd-snapshot-upload.sh --version x86_64-v1
```

This will:
- Package the AVD snapshot (config, disk images, snapshot data)
- Sanitize absolute paths (replace SDK/home paths with placeholders)
- Compress with zstd (level 19)
- Split into <2GB chunks for GitHub Releases
- Create release `emulator-snapshot-x86_64-v1` on `whizvoice/whizvoiceapp`

### 9. Verify the upload

```bash
gh release view emulator-snapshot-x86_64-v1 --repo whizvoice/whizvoiceapp
```

## Important Notes

- The `sed -i` commands in `avd-snapshot-upload.sh` use macOS syntax (`sed -i ''`). On Linux, you may need to change them to `sed -i` (without the empty string). The download script already handles this, but the upload script does not yet. Fix lines 114-115 if you get sed errors.
- Kill the emulator before running the upload script (it checks for this).
- The snapshot includes the userdata disk image which contains the installed apps and their login state.
- Total upload size is typically 1-3GB depending on how much app data is stored.
