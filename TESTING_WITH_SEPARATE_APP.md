# 🧪 Testing Without Interfering with Your Regular App

## Problem

When running tests on your Android device, you don't want to mess with your regular WhizVoice app installation that you use daily.

## Solution: Debug Build Variant with Separate Package

We've configured a **debug build variant** that creates a completely separate app installation:

- **Regular App**: `com.example.whiz` → "Whiz Voice"
- **Debug App**: `com.example.whiz.debug` → "WhizVoice Debug"

Both can coexist on your device without any interference.

## 🚀 Recommended Workflow

### Just Run Tests (Most Common)

```bash
./test_with_latest_changes.sh
```

This will:

- Build your latest code
- Run all tests with your changes
- **No reinstall needed** - tests automatically use latest code

### Manual Testing + Automated Tests

```bash
./test_with_latest_changes.sh --manual
```

This will:

- Build and install the latest debug APK
- Run all tests
- Let you manually test the debug app with your changes

### Tests Only (Skip Any Installation)

```bash
./test_with_latest_changes.sh --skip-install
```

## When Do You Need to Reinstall?

### ❌ **NO Reinstall Needed For:**

- **Unit tests** - Run on JVM with latest code
- **Instrumented tests** - Gradle auto-installs latest test APK
- **Most development workflow** - Just testing your changes

### ✅ **Reinstall Needed For:**

- **Manual testing** - Want to see/use new features in the debug app
- **UI changes** - Want to see visual changes in the actual app
- **Runtime behavior** - Want to experience new functionality

## Quick Start

### 1. First Time Setup

```bash
./install_debug_for_testing.sh
```

### 2. Daily Testing Workflow

```bash
# After making code changes, just run:
./test_with_latest_changes.sh
```

### 3. Manual Testing New Features

```bash
# When you want to try new features manually:
./test_with_latest_changes.sh --manual
```

## What You'll See

After installation, you'll have:

- **Whiz Voice** - Your regular app (unchanged)
- **WhizVoice Debug** - Testing version with debug info

The debug version has:

- Different package name (`com.example.whiz.debug`)
- Different app name ("WhizVoice Debug")
- Debug logging enabled
- Version suffix "-debug"

## Benefits

✅ **No Interference**: Your regular app stays untouched
✅ **Real Device Testing**: Tests run on actual device, not just emulator
✅ **Separate Data**: Debug version has its own database and preferences
✅ **Easy Cleanup**: Uninstall debug version when done
✅ **Visual Distinction**: Different names make it clear which is which

## Commands

```bash
# Install debug version for testing
./install_debug_for_testing.sh

# Run all tests on debug version
./run_tests_on_debug.sh

# Run only unit tests
./gradlew testDebugUnitTest

# Run only instrumented tests on debug
./gradlew connectedDebugAndroidTest

# Uninstall debug version when done
adb uninstall com.example.whiz.debug

# Check what's installed
adb shell pm list packages | grep whiz
```

## Alternative: Use Android Emulator

If you prefer not to have a debug version on your device, you can also:

1. Start an Android emulator:

   ```bash
   # List available emulators
   emulator -list-avds

   # Start an emulator
   emulator -avd <your_emulator_name>
   ```

2. Run tests on the emulator:
   ```bash
   ./run_all_tests.sh
   ```

## Test Reports

After running tests, reports are available at:

- **Unit Tests**: `app/build/reports/tests/testDebugUnitTest/index.html`
- **Instrumented Tests**: `app/build/reports/androidTests/connected/debug/index.html`

## Cleanup

When you're done testing:

```bash
# Remove debug version
adb uninstall com.example.whiz.debug

# Keep your regular app untouched
# (com.example.whiz stays installed)
```

## Technical Details

The debug build variant is configured in `app/build.gradle.kts`:

```kotlin
buildTypes {
    debug {
        applicationIdSuffix = ".debug"
        isDebuggable = true
        versionNameSuffix = "-debug"
        resValue("string", "app_name", "WhizVoice Debug")
    }
    // ... release build
}
```

This ensures complete separation between your production and testing environments.

## 👀 How to Tell Them Apart

After installation, you'll **easily** distinguish between the two apps:

### **1. App Name & Icon in Launcher**

- **Production**: "Whiz Voice"
- **Debug**: "🧪 WhizVoice DEBUG" (with emoji!)

### **2. App Theme Colors**

- **Production**: Your normal theme colors
- **Debug**: Orange/red debug theme throughout the app

### **3. Package Names**

- **Production**: `com.example.whiz`
- **Debug**: `com.example.whiz.debug`

### **4. Version Information**

- **Production**: "1.0"
- **Debug**: "1.0-debug"

### **5. Visual Debug Indicators** (Debug Only)

- Debug badge components throughout the UI
- Orange accent colors in key areas
- Clear "DEBUG BUILD" indicators

## Quick Check: What's Installed?

```bash
./check_installed_apps.sh
```

This will show you exactly which versions are installed and their details.
