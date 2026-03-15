# Fix: Re-export AVD Snapshot with QCOW2 Internal Snapshots

## Problem

The uploaded AVD snapshot archive is missing QCOW2 internal snapshot tags. The `snapshots/baseline_clean/` directory exists with RAM/metadata, but each QCOW2 overlay file (`cache.img.qcow2`, `userdata-qemu.img.qcow2`, `encryptionkey.img.qcow2`) must also contain an internal snapshot named `baseline_clean`. Without these tags, the emulator logs:

```
WARNING | Device 'cache' does not have the requested snapshot 'baseline_clean'
WARNING | Failed to load snapshot 'baseline_clean'
```

...and falls back to cold boot.

## CI Evidence

After extracting the snapshot archive in CI:
```
cache.img.qcow2:         NO SNAPSHOTS FOUND
userdata-qemu.img.qcow2: NO SNAPSHOTS FOUND
encryptionkey.img.qcow2:  NO SNAPSHOTS FOUND
```

## Steps

### 1. Verify current state on this machine

```bash
AVD_DIR="$HOME/.android/avd/whiz-test-device.avd"
for f in cache.img.qcow2 userdata-qemu.img.qcow2 encryptionkey.img.qcow2; do
    echo "=== $f ==="
    qemu-img snapshot -l "$AVD_DIR/$f" 2>&1
done
```

**If `baseline_clean` appears in all three:** The snapshot is fine on disk. Skip to step 3 — the issue is in how the archive is built (tar/zstd may be truncating or the upload script is copying raw `.img` files over the `.qcow2` files).

**If `baseline_clean` is missing:** Continue to step 2.

### 2. Re-create the snapshot (only if step 1 shows missing tags)

```bash
# Boot emulator
emulator -avd whiz-test-device &

# Wait for boot
adb wait-for-device
adb shell 'while [[ "$(getprop sys.boot_completed)" != "1" ]]; do sleep 2; done'

# Get device into desired clean state (dismiss any dialogs, etc.)
# Then save the snapshot:
adb emu avd snapshot save baseline_clean

# Kill emulator
adb emu kill
```

Verify again with the `qemu-img snapshot -l` commands from step 1. All three QCOW2 files should now list `baseline_clean`.

### 3. Re-package and re-upload

Run the existing upload script (likely `avd-snapshot-upload.sh`). After it creates the `.tar.zst` archive but before uploading, verify the archive contains valid QCOW2 snapshots:

```bash
# Extract to a temp dir and verify
TMPDIR=$(mktemp -d)
zstd -d whiz-avd-snapshot.tar.zst --stdout | tar -xf - -C "$TMPDIR"
for f in cache.img.qcow2 userdata-qemu.img.qcow2 encryptionkey.img.qcow2; do
    echo "=== $f ==="
    qemu-img snapshot -l "$TMPDIR/$f" 2>&1
done
rm -rf "$TMPDIR"
```

All three must show `baseline_clean`. If they don't, the upload script itself is stripping them — check if it's doing any QCOW2 compaction (`qemu-img convert`) which would remove internal snapshots.

### 4. Verify fix in CI

After uploading, trigger the CI workflow and check for:
- `qemu-img snapshot -l` output showing `baseline_clean` after extraction
- Emulator log: `INFO | Loading snapshot 'baseline_clean'...` followed by successful load (no "cold boot" message)
