# Create x86_64 Emulator Snapshot for CI

## Status (March 2026)

**Not fully working yet.** The snapshot creation works and CI downloads it successfully, but the emulator replaces the QCOW2 overlay on cold boot, losing installed apps. See "Open issues" at the bottom. The Google OAuth sign-in issue has been fixed (was caused by outdated GMS version).

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

### Cache partition disabled via `disk.cachePartition=no`

The emulator's cache partition causes snapshot loading failures on CI. The emulator creates an internal QCOW2 overlay on top of `cache.img.qcow2` for writes, and QEMU's `loadvm` checks the overlay (which has no snapshot tag) rather than the base file. This causes "Device 'cache' does not have the requested snapshot 'baseline_clean'".

**Previous attempt:** The `-no-cache` flag does NOT fix this — it controls the HTTP proxy cache, not the cache partition block device.

**Fix:** Set `disk.cachePartition=no` in `config.ini` and delete `cache.img`/`cache.img.qcow2` before emulator start. This prevents the emulator from creating a cache block device at all. QEMU's `loadvm` only checks block devices that exist. The cache partition on API 36 is unused (legacy OTA/Dalvik cache; ART replaces Dalvik, and A/B updates don't use it).

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
4. **Boot**: Emulator boots with `-snapshot baseline_clean` (full snapshot load). Cache partition is disabled via `disk.cachePartition=no` in config.ini (prevents cache device from being created, which would otherwise fail snapshot loading)

### Droplet

- **Setup script**: `scripts/setup-snapshot-droplet.sh`
- **SDK path**: `/opt/android-sdk`
- **AVD path**: `/root/.android/avd/whiz-test-device.avd`
- Emulator version: `36.4.10.0` (build `15004761`)

## Open Issues (March 2026)

### Cold boot QCOW2 replacement (resolved)

**Diagnosed:** The emulator creates an internal QCOW2 overlay on top of `cache.img.qcow2` for writes. QEMU's `loadvm` checks the overlay (which has no snapshot tag) rather than the base file, causing "Device 'cache' does not have the requested snapshot 'baseline_clean'".

**Previous attempt:** `-no-cache` flag did NOT work — it controls the HTTP proxy cache, not the cache partition block device. The cache.img.qcow2 file still had the tag (verified by qemu-img after boot), but the emulator's internal overlay didn't.

**Fix:** Set `disk.cachePartition=no` in `config.ini` and delete `cache.img`/`cache.img.qcow2` before emulator start. This prevents the cache block device from being created at all. QEMU's `loadvm` only checks block devices that exist. The cache partition on API 36 is unused (legacy OTA/Dalvik cache).

### WhatsApp crashes emulator with swiftshader_indirect

Opening WhatsApp causes a segfault in the swiftshader GPU renderer on the droplet. `swangle` handles it but doesn't work on headless CI. Not currently blocking (CI doesn't open WhatsApp), but will need a solution for future WhatsApp integration tests.
