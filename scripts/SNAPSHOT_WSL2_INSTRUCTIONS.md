# Fix: Create AVD Snapshot on WSL2 with KVM

## Problem

The emulator snapshot must be created on the same hypervisor type as CI. CI runs on Linux with KVM, but:
- **Windows (WHPX)** snapshots → `Reason: host hypervisor has changed` on CI
- **macOS (HVF)** snapshots → same error

Only a **KVM snapshot** will load on the KVM-based CI runners.

WSL2 on your Windows machine may support KVM, which would let you create a compatible snapshot without needing a separate Linux machine.

## Step 1: Check if KVM is available in WSL2

Open your WSL2 terminal and run:

```bash
ls /dev/kvm
```

- If it prints `/dev/kvm` → KVM is available, proceed to step 2
- If "No such file or directory" → you need to enable nested virtualization, see below

### Enabling nested virtualization in WSL2

1. Open PowerShell **as Administrator** on Windows
2. Check your WSL version:
   ```powershell
   wsl --version
   ```
   You need WSL version 2.0.0+ and Windows 11 (or recent Windows 10 builds)

3. Create or edit `%USERPROFILE%\.wslconfig`:
   ```ini
   [wsl2]
   nestedVirtualization=true
   ```

4. Restart WSL:
   ```powershell
   wsl --shutdown
   ```

5. Re-open WSL2 and check again:
   ```bash
   ls /dev/kvm
   ```

If it still doesn't work, your CPU or Windows version may not support nested virtualization in WSL2. In that case, you'd need a Linux cloud VM (GCP, AWS, etc.) with KVM support.

## Step 2: Install Android SDK in WSL2

```bash
# Install Java
sudo apt update
sudo apt install -y openjdk-21-jdk unzip wget

# Download Android command-line tools
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
mv cmdline-tools latest

# Set up environment
echo 'export ANDROID_HOME=~/android-sdk' >> ~/.bashrc
echo 'export ANDROID_SDK_ROOT=~/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc

# Accept licenses and install components
yes | sdkmanager --licenses
sdkmanager "platform-tools" "emulator" "system-images;android-36;google_apis_playstore;x86_64"
```

## Step 3: Create the AVD and snapshot

```bash
# Create AVD
avdmanager create avd -n whiz-test-device -k "system-images;android-36;google_apis_playstore;x86_64" --device "pixel_6"

# Boot emulator (with display forwarded — you'll need an X server like VcXsrv on Windows,
# or use -no-window and connect via adb/scrcpy)
emulator -avd whiz-test-device -gpu swiftshader_indirect &

# Wait for boot
adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
```

### Interactive setup

At this point you need to log into the app and set up the clean state. To get a display:

**Option A: X11 forwarding (recommended)**
- Install VcXsrv or Xming on Windows
- In WSL2: `export DISPLAY=$(cat /etc/resolv.conf | grep nameserver | awk '{print $2}'):0`
- Then run the emulator without `-no-window`

**Option B: scrcpy**
- Install scrcpy in WSL2: `sudo apt install scrcpy`
- Run `scrcpy` after the emulator boots

**Option C: adb + Android Studio on Windows**
- Run emulator headless in WSL2 with `-no-window`
- Forward adb: the emulator will listen on a port, connect from Windows Android Studio

Once the device is in the desired clean state:

```bash
# Save the snapshot
adb emu avd snapshot save baseline_clean

# Kill the emulator
adb emu kill

# Verify QCOW2 internal snapshots exist
AVD_DIR="$HOME/.android/avd/whiz-test-device.avd"
for f in cache.img.qcow2 userdata-qemu.img.qcow2 encryptionkey.img.qcow2; do
    echo "=== $f ==="
    qemu-img snapshot -l "$AVD_DIR/$f"
done
```

All three files must show `baseline_clean`.

## Step 4: Upload the snapshot

You'll need `gh` CLI and `zstd` installed in WSL2:

```bash
sudo apt install -y zstd
# Install gh: https://github.com/cli/cli/blob/trunk/docs/install_linux.md

gh auth login
```

Then run the upload script from the repo:

```bash
cd /path/to/whizvoiceapp
./scripts/avd-snapshot-upload.sh --version x86_64-v1
```

## Step 5: Verify in CI

After upload, trigger the CI workflow and check for:
- `qemu-img snapshot -l` showing `baseline_clean` after extraction
- `INFO | Loading snapshot 'baseline_clean'...` followed by successful load (no "host hypervisor has changed")
- No `adb: device unauthorized` errors
