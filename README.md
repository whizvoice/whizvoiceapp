# whiz voice android app

## developer set up

i like to

```
mkdir whiz
cd whiz
git clone git@github.com:whizvoice/whizvoice.git
git clone git@github.com:whizvoice/whizvoiceapp.git
ln -s whizvoiceapp/.cursorrules .cursorrules
```

and then open cursor with whiz as the project folder so that it has access to update both the webapp and the android app as necessary

## testing

### install precommit

### unit tests

cd whizvoiceapp
./run_tests.sh unit

# Run a specific test

./run_tests.sh specific ChatsListViewModelTest
