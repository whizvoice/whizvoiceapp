# Create x86_64 Emulator Snapshot for CI

## Status (March 24, 2026)

**Not fully working yet.** The snapshot needs to be recreated with `disk.cachePartition = no` set before first boot. OAuth signing is now fixed (explicit `signingConfigs` in `build.gradle.kts` + keystore setup moved before Gradle cache in CI). The emulator boots and adb works, but QEMU2 hangs/crashes during test execution — may be instability under load on CI runners.

## Goal

Create an Android emulator snapshot (x86_64, API 36) with WhatsApp, Fitbit, and Google Maps pre-installed and logged in, then upload it to GitHub Releases so CI can use it for autofix verification tests.

## Prerequisites

- x86_64 Linux machine with KVM (we use a DigitalOcean dedicated-CPU droplet)
- At least 16GB RAM, 48GB disk
- A phone number for WhatsApp verification (SMS code)
- Google account: `REDACTED_TEST_EMAIL`
- `gh` CLI authenticated with access to `whizvoice/whizvoiceapp` repo

## Creating the Snapshot

### Automated setup (recommended)

Use the droplet setup script which handles SDK installation, display setup, and emulator boot:

```bash
scp scripts/setup-snapshot-droplet.sh root@<droplet-ip>:~/
ssh root@<droplet-ip>
bash setup-snapshot-droplet.sh
```

This starts a noVNC server at `http://<droplet-ip>:6080/vnc.html` for interactive app setup.

### Manual steps required

After the emulator boots, you must interactively:

1. Add the Google account (Settings > Accounts > Google > `REDACTED_TEST_EMAIL`)
2. Install and log into WhatsApp (SMS verification required)
3. Install and log into Fitbit
4. Verify Google Maps is available

Install apps via adb (in a separate SSH session):
```bash
export PATH="/opt/android-sdk/platform-tools:/opt/android-sdk/emulator:$PATH"

# Download APKs
mkdir -p /tmp/app-apks
gh release download app-apks-for-snapshot --repo whizvoice/whizvoiceapp --dir /tmp/app-apks

# Install WhatsApp
adb install -r /tmp/app-apks/*.apk

# Install Fitbit
mkdir -p /tmp/fitbit_bundle
unzip /tmp/app-apks/*.apkm -d /tmp/fitbit_bundle -x "META-INF/*" "*.url" "info.json" "icon.png"
adb install-multiple -r /tmp/fitbit_bundle/*.apk
```

### Save and upload

After interactive setup, press Enter in the setup script's terminal to save the snapshot. Then upload:

```bash
gh auth login
git clone https://github.com/whizvoice/whizvoiceapp.git
cd whizvoiceapp
./scripts/avd-snapshot-upload.sh --version x86_64-v2
```

The upload script packages the AVD files, sanitizes absolute paths, compresses with zstd, and uploads to GitHub Releases.

**Disk space note**: The upload script copies files to a staging directory before compressing. If disk is tight, you can use symlinks instead of copies — see the staging code in `avd-snapshot-upload.sh`.

## Technical Details

### QCOW2 overlay system

The emulator stores app data in a QCOW2 overlay chain:
- `userdata-qemu.img` — base image (~6MB). **Despite the `.img` extension, this is QCOW2 format, not raw.**
- `userdata-qemu.img.qcow2` — overlay with all changes (installed apps, accounts, settings)
- The overlay has a **backing file reference** pointing to the base. The emulator needs this to read data.

### NEVER use qemu-img on snapshot QCOW2 files

These operations will break the QCOW2 files beyond repair:
- `qemu-img convert` — removes the backing file reference
- `qemu-img convert -O qcow2` (compaction) — same problem
- `qemu-img rebase` — re-adds a reference but the emulator treats the result differently

**Always use QCOW2 files exactly as the emulator created them.**

### Path rewriting on CI

The download script (`avd-snapshot-download.sh`) handles path differences between the build machine and CI:
- Rewrites `.ini` file paths (`__SDK_ROOT__` / `__HOME__` placeholders)
- Rewrites `snapshot.pb` paths
- Rewrites QCOW2 backing file paths to relative using `qemu-img rebase -u -F qcow2`

The backing file format MUST be `qcow2` (not `raw`) because `userdata-qemu.img` is QCOW2 despite its `.img` extension.

### Cache partition must be disabled BEFORE snapshot creation

The Android emulator's modified QEMU requires ALL block devices to have the snapshot tag during `loadvm`. The cache device always gets a fresh in-memory overlay without the tag, causing "Device 'cache' does not have the requested snapshot 'baseline_clean'".

**Things that DON'T work** (all tried on CI):
- `-no-cache` flag — controls HTTP proxy cache, not the cache partition
- `disk.cachePartition = no` in config.ini/hardware-qemu.ini after AVD creation — emulator ignores it, still creates cache device
- Deleting cache.img/cache.img.qcow2 — emulator still creates cache device
- Creating fresh cache.img.qcow2 with snapshot tag — emulator creates in-memory overlay on top, QEMU checks the overlay (no tag) not the file

**Fix:** Set `disk.cachePartition = no` in config.ini BEFORE first emulator boot (during AVD creation). The `setup-snapshot-droplet.sh` script does this. When the snapshot is saved without a cache device, `loadvm` on CI won't look for a cache snapshot tag. The cache partition on API 36 is unused (legacy OTA/Dalvik cache).

### GPU rendering modes

| Mode | Headless CI | WhatsApp | Use for |
|------|------------|----------|---------|
| `swiftshader_indirect` | Works | Crashes emulator | CI (doesn't open WhatsApp) |
| `swangle` | Fails to boot | Works | Droplet interactive setup |
| `gpu auto` | May segfault | Varies | Not recommended |

### Google OAuth

- Google Cloud Console needs an Android OAuth client for `com.example.whiz.debug` with the CI debug keystore's SHA-1
- Test account must be in the OAuth consent screen's test users list (if in "Testing" mode)
- GMS ships at `26.09.31` with the system image. Can be updated from APKMirror (x86+x86_64 variant, Android 15+). **Always install ALL split APKs** — partial installs break GMS entirely.

### CI snapshot pipeline

1. **Download**: `avd-snapshot-download.sh` extracts snapshot, rewrites paths in `.ini` files, `snapshot.pb`, and QCOW2 backing references
2. **Backup**: Workflow backs up QCOW2 files before the `emulator-runner` action modifies `config.ini`
3. **Restore**: `pre-emulator-launch-script` restores QCOW2 files and patches `config.ini` right before emulator launch
4. **Boot**: Emulator boots with `-snapshot baseline_clean` (full snapshot load). Cache partition was disabled at snapshot creation time (`disk.cachePartition=no` in config.ini before first boot), so the snapshot doesn't include cache device state

### Droplet

- **Setup script**: `scripts/setup-snapshot-droplet.sh`
- **SDK path**: `/opt/android-sdk`
- **AVD path**: `/root/.android/avd/whiz-test-device.avd`
- Emulator version: `36.4.10.0` (build `15004761`)

## Open Issues (March 24, 2026)

### Snapshot needs recreation with cache partition disabled

**Root cause:** The Android emulator's modified QEMU requires ALL block devices to have the snapshot tag during `loadvm`. The emulator always creates a fresh in-memory overlay on the cache device. This overlay has no snapshot tag, so `loadvm` fails with "Device 'cache' does not have the requested snapshot 'baseline_clean'".

**What didn't work (all tried):** `-no-cache` flag, `disk.cachePartition = no` after AVD creation, deleting cache files, creating fresh cache.img.qcow2 with tag. The emulator ignores all runtime attempts to disable the cache device.

**Fix:** Set `disk.cachePartition = no` BEFORE first emulator boot during snapshot creation. The `setup-snapshot-droplet.sh` script now does this after `avdmanager create avd`. The snapshot is saved without a cache device, so `loadvm` won't look for a cache snapshot tag on CI.

**Next step:** SSH to droplet, run updated `setup-snapshot-droplet.sh`, install apps, log in interactively, upload with `avd-snapshot-upload.sh --version x86_64-v4`.

### QEMU2 hangs/crashes during test execution

On CI run 23507806579 (March 24, 2026), the emulator booted successfully (WhatsApp detected, adb working), but QEMU2 hung during the actual test:
- `detected a hanging thread 'QEMU2 main loop'. No response for 17600 ms`
- `detected a hanging thread 'QEMU2 CPU0 thread'. No response for 23996 ms`
- `ptrace: No such process`

This may be related to the snapshot issue (emulator running without snapshot restore) or CI runner resource constraints. Needs investigation after snapshot is recreated.

### WhatsApp crashes emulator with swiftshader_indirect

Opening WhatsApp causes a segfault in the swiftshader GPU renderer on the droplet. `swangle` handles it but doesn't work on headless CI. Not currently blocking (CI doesn't open WhatsApp), but will need a solution for future WhatsApp integration tests.

### No test artifacts uploaded on failure

The "Upload test artifacts" step didn't run when the emulator crashed. The `if: always()` condition should trigger it, but the job may have been killed before reaching that step. May need to investigate whether the emulator-runner action's failure mode prevents subsequent steps.
