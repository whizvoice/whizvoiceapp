# whiz voice android app

## developer set up

i like to

```
mkdir whiz
cd whiz
git clone git@github.com:whizvoice/whizvoice.git
git clone git@github.com:whizvoice/whizvoiceapp.git
ln -s whizvoiceapp/.cursor .cursor
# if exists, to find test output easier
ln -s whizvoiceapp/test_output.log test_output.log
```

and then open cursor with whiz as the project folder so that it has access to update both the webapp and the android app as necessary

### optional: android claude code skills

to install android-specific claude code skills (feature delivery, bug investigation, code review, etc.):

```
git clone https://github.com/tsurantino/android-skills.git ~/android-skills
ln -s ~/android-skills/skills whizvoiceapp/.claude/skills
```

the symlink is gitignored so each developer sets it up locally.

## testing

### run tests on device

plug in your google pixel 8

```
cd whizvoiceapp
./run_tests_on_debug.sh
```

### run tests on emulator

```
cd whizvoiceapp
./run_tests_on_emulator.sh
```

#### emulator setup (one-time)

install the emulator and a system image matching CI (API 31, 320x640):

```
sdkmanager "emulator" "platforms;android-31" "system-images;android-31;default;arm64-v8a"
avdmanager create avd -n "ci_small" -k "system-images;android-31;default;arm64-v8a" -d "pixel"
cat >> ~/.android/avd/ci_small.avd/config.ini << 'EOF'
hw.lcd.width=320
hw.lcd.height=640
hw.lcd.density=160
skin.name=320x640
EOF
```

to start the emulator:

```
emulator -avd ci_small -memory 4096 &
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done' && echo "Booted"
```

make sure your physical phone is disconnected (or only the emulator shows in `adb devices`) before running tests.

to shut down: `adb -s emulator-5554 emu kill` or close the window.

### get test logs from server

```
sudo journalctl -u whizvoice --since "Aug 05 07:15:36 UTC" --no-pager > whizvoice_server_logs.txt
```

### integration tests

#### set up

```
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
source export_anthropic_key.sh
```

export_anthropic_key.sh should look like
```
#!/bin/bash
export ANTHROPIC_API_KEY=your-key-here
```

#### run

```
./venv/bin/pytest run_screen_agent_tests.py
```
