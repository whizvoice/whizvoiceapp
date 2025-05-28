# GitHub Actions Workflows

This directory contains GitHub Actions workflows for automated testing and continuous integration of the WhizVoice Android app.

## Workflows

### 1. `pr-tests.yml` - Pull Request Unit Tests ⭐

**Primary workflow for PR validation**

- **Triggers**: On pull requests to `main` or `develop` branches
- **Purpose**: Runs unit tests to ensure code changes don't break existing functionality
- **What it does**:
  - Sets up Android development environment
  - Runs all unit tests in the project
  - Reports test results directly in the PR
  - Fails the check if any tests fail

### 2. `android-tests.yml` - Comprehensive Test Suite

- **Triggers**: On pull requests and pushes to `main`
- **Purpose**: Runs unit tests with detailed reporting
- **Features**:
  - Uploads test artifacts for debugging
  - Provides detailed test reports
  - Caches Gradle dependencies for faster builds

### 3. `android-ci.yml` - Full CI Pipeline

- **Triggers**: On pull requests and pushes to `main`
- **Purpose**: Complete CI pipeline with linting, building, and testing
- **Jobs**:
  - **Lint**: Checks code style and potential issues
  - **Build**: Compiles and builds debug APK
  - **Test**: Runs unit tests with reporting

## How It Works

### For Pull Requests

1. When you create a PR, the `pr-tests.yml` workflow automatically runs
2. It sets up an Ubuntu environment with Android SDK and JDK 21
3. Runs `./gradlew test` in the project directory
4. Reports results directly in the PR with ✅ or ❌ status
5. If tests fail, the PR check fails and shows which tests broke

### Test Results

- **Pass**: Green checkmark ✅ appears on your PR
- **Fail**: Red X ❌ appears with details about which tests failed
- **In Progress**: Yellow circle 🟡 while tests are running

## Benefits

### 🛡️ **Prevents Breaking Changes**

- Catches bugs before they reach the main branch
- Ensures all existing functionality still works

### 🚀 **Faster Development**

- Automated testing saves manual testing time
- Quick feedback on code changes

### 👥 **Team Collaboration**

- Everyone can see test status before reviewing code
- Consistent testing across all contributors

### 📊 **Visibility**

- Clear test reports show exactly what passed/failed
- Historical view of test trends

## Setup Requirements

The workflows are configured to work automatically, but ensure:

1. **Repository Settings**:

   - Go to Settings → Branches
   - Add branch protection rule for `main`
   - Check "Require status checks to pass before merging"
   - Select "Run Unit Tests" as required check

2. **Permissions**:
   - Workflows have read access to repository
   - Can post comments on PRs for test results

## Local Testing

Before pushing, you can run the same tests locally:

```bash
# Option 1: Use the test script
./test-ci-locally.sh

# Option 2: Use the run_tests script
./run_tests.sh unit

# Option 3: Run manually
./gradlew test
```

This runs the exact same tests that GitHub Actions will run.

## Troubleshooting

### Common Issues

**Tests fail in CI but pass locally:**

- Check if you committed all necessary files
- Ensure test dependencies are in `build.gradle.kts`
- Verify Android SDK versions match

**Workflow doesn't trigger:**

- Check that you made changes to the repository
- Ensure PR targets `main` or `develop` branch

**Build fails:**

- Check JDK version (should be 21)
- Verify Gradle wrapper is committed
- Check for missing dependencies

### Getting Help

If workflows fail:

1. Click on the failed check in your PR
2. View the detailed logs
3. Look for error messages in the "Run unit tests" step
4. Fix the failing tests locally and push again

## File Structure

```
whizvoiceapp/
├── .github/
│   ├── workflows/
│   │   ├── pr-tests.yml          # Main PR testing (recommended)
│   │   ├── android-tests.yml     # Detailed test reporting
│   │   └── android-ci.yml        # Full CI pipeline
│   └── README.md                 # This file
├── test-ci-locally.sh            # Local testing script
├── run_tests.sh                  # Unit test runner
└── app/                          # Android app source
```

## Next Steps

1. **Commit these workflows** to your repository
2. **Set up branch protection** in GitHub settings
3. **Create a test PR** to see the workflows in action
4. **Add more tests** as you develop new features

The workflows will automatically start working once you push them to your repository! 🎉
