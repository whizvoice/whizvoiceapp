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
