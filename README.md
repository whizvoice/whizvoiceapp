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

### get test logs from server

```
sudo journalctl -u whizvoice --since "Aug 05 07:15:36 UTC" --no-pager > whizvoice_server_logs.txt
```

### set up droidrun for integration testing of screen agent capabilities

Screen agent functionality cannot be tested using the regular Android framework because accessibility services are prevented from starting while the instrumentation framework is running due to security reasons. So we are using Droidrun.

reference: https://docs.droidrun.ai/v3/quickstart

```
python3 -m venv venv
source venv/bin/activate
pip install -r droidrun/requirements-screen-agent-testing.txt
droidrun setup
```

Put your anthropic key in droidrun/export_anthropic_key.sh

```
#!/bin/bash
export ANTHROPIC_API_KEY="YOUR-KEY-HERE"
```

Before running DroidRun you'll want to enable Droidrun Portal in Accessibility settings, and switch to the Droidrun keyboard (globe button on your regular keyboard)

run integration tests

```
./run_screen_agent_tests_with_droidrun.sh
```

To stop droidrun you need to go to Accessibility Services > Apps > Droidrun Portal
and turn it off.
To switch from the droidrun keyboard, click the globe button under Droidrun Keyboard ON at the bottom of the screen.
