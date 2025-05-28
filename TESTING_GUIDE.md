# Testing Guide for Whiz Android App

## Overview

This guide covers the comprehensive testing strategy for the Whiz Android app, including unit tests, integration tests, and UI tests.

## Test Structure

```
app/src/
├── test/                           # Unit Tests (JVM)
│   └── java/com/example/whiz/
│       ├── TestUtils.kt           # Common test utilities
│       ├── TestData.kt            # Mock data factory
│       └── data/repository/
│           └── WhizRepositoryIntegrationTest.kt  # Repository integration tests
└── androidTest/                   # Instrumented Tests (Android)
    └── java/com/example/whiz/ui/screens/
        ├── ChatsListScreenTest.kt  # UI tests for chat list
        ├── ChatScreenTest.kt       # UI tests for chat screen
        ├── LoginScreenTest.kt      # UI tests for login screen
        └── SettingsScreenTest.kt   # UI tests for settings screen
```

## Test Types

### 1. Unit Tests (JVM)

**Location**: `app/src/test/`
**Framework**: JUnit 4, Mockito, Truth, Turbine
**Purpose**: Test business logic, data transformations, and repository operations

#### WhizRepositoryIntegrationTest

Tests the repository layer with mocked API service:

- ✅ Chat creation and error handling
- ✅ Chat retrieval and caching
- ✅ Message operations (add user/assistant messages)
- ✅ Chat title derivation and truncation
- ✅ Incremental sync operations
- ✅ Error handling and fallback behavior
- ✅ Message count operations
- ✅ Chat persistence logic

**Key Features Tested:**

- API error handling with fallback to cached data
- Message type handling (USER vs ASSISTANT)
- Chat title truncation for long messages
- Incremental sync with server timestamps
- SharedPreferences integration for caching

### 2. UI Tests (Instrumented)

**Location**: `app/src/androidTest/`
**Framework**: Compose UI Testing, AndroidJUnit4
**Purpose**: Test user interface interactions and screen behavior

#### ChatsListScreenTest

Tests the main chat list interface:

- ✅ Empty state display
- ✅ Chat list rendering
- ✅ Loading indicator
- ✅ FAB (New Chat) functionality
- ✅ Settings button navigation
- ✅ Chat item click handling
- ✅ Callback triggering

#### ChatScreenTest

Tests the individual chat interface:

- ✅ Empty conversation state
- ✅ Message display (user and assistant)
- ✅ Input field presence
- ✅ Microphone button display
- ✅ Navigation buttons (back, settings)
- ✅ Message sending functionality
- ✅ Loading states
- ✅ Callback handling

#### LoginScreenTest

Tests the authentication interface:

- ✅ Welcome message display
- ✅ Google Sign-In button
- ✅ Loading states
- ✅ Button state management
- ✅ Sign-in callback triggering

#### SettingsScreenTest

Tests the settings interface:

- ✅ Settings title and navigation
- ✅ Voice settings section
- ✅ Speech speed and pitch sliders
- ✅ Sign out functionality
- ✅ Delete all chats functionality
- ✅ All callback handling

## Running Tests

### Quick Test Run

```bash
# Run only unit tests
./gradlew testDebugUnitTest

# Run only instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

### Comprehensive Test Run

```bash
# Run all tests with detailed reporting
./run_all_tests.sh
```

### Individual Test Classes

```bash
# Run specific unit test class
./gradlew testDebugUnitTest --tests="*WhizRepositoryIntegrationTest*"

# Run specific UI test class
./gradlew connectedAndroidTest --tests="*ChatsListScreenTest*"
```

## Test Reports

After running tests, reports are generated at:

- **Unit Tests**: `app/build/reports/tests/testDebugUnitTest/index.html`
- **Instrumented Tests**: `app/build/reports/androidTests/connected/index.html`

## Test Dependencies

### Unit Testing

```kotlin
testImplementation("org.mockito:mockito-core:5.8.0")
testImplementation("org.mockito:mockito-inline:5.2.0")
testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
testImplementation("com.google.truth:truth:1.1.4")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
testImplementation("androidx.arch.core:core-testing:2.2.0")
testImplementation("app.cash.turbine:turbine:1.0.0")
```

### Instrumented Testing

```kotlin
androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.8")
androidTestImplementation("androidx.test:core:1.5.0")
androidTestImplementation("androidx.test:runner:1.5.2")
androidTestImplementation("androidx.test:rules:1.5.0")
androidTestImplementation("androidx.test.ext:junit:1.1.5")
```

## Testing Strategy

### Remote-First Architecture

Since the app uses a remote-first architecture with Supabase:

- **Unit tests** mock the API service to test business logic
- **Integration tests** verify repository behavior with mocked dependencies
- **UI tests** focus on user interactions and screen states
- **No Room database tests** (since local storage is minimal)

### Mocking Strategy

- **API Service**: Fully mocked for predictable test behavior
- **SharedPreferences**: Mocked for caching tests
- **Android Framework**: Mocked using testOptions configuration

### Test Coverage Areas

#### ✅ Covered

- Repository business logic and error handling
- UI component rendering and interactions
- User input handling and validation
- Navigation and callback triggering
- Loading states and empty states
- Authentication flow
- Settings management

#### 🚫 Excluded (As Requested)

- Input field clearing after sending
- Character limits and validation
- Voice recording indicators
- Button states during loading
- Offline/online behavior
- Performance tests
- Volume settings (not implemented)

## Best Practices

### Unit Tests

1. **Use descriptive test names** that explain the scenario
2. **Follow Given-When-Then structure** for clarity
3. **Mock external dependencies** to isolate units under test
4. **Use lenient() for shared mocks** to avoid unnecessary stubbing exceptions
5. **Test both success and error scenarios**

### UI Tests

1. **Test user-visible behavior** rather than implementation details
2. **Use semantic finders** (text, content description) over test tags
3. **Verify callbacks are triggered** for user interactions
4. **Test different UI states** (loading, empty, populated)
5. **Keep tests focused** on single screen functionality

### General

1. **Run tests frequently** during development
2. **Keep test data factories** for consistent mock objects
3. **Use meaningful assertions** with clear error messages
4. **Maintain test independence** - tests should not depend on each other
5. **Update tests when functionality changes**

## Continuous Integration

The project includes GitHub Actions workflows for automated testing:

- **PR Tests**: Run on every pull request
- **Full CI**: Comprehensive testing with lint, build, and test
- **Android Tests**: Detailed test reporting with artifacts

## Troubleshooting

### Common Issues

1. **Tests fail with "Android Log not mocked"**

   - Solution: Ensure `testOptions.unitTests.isReturnDefaultValues = true` in build.gradle

2. **Mockito unnecessary stubbing exceptions**

   - Solution: Use `lenient()` for shared mock setups

3. **Compose UI tests fail to find elements**

   - Solution: Check content descriptions and text exactly match the UI

4. **Instrumented tests don't run**
   - Solution: Ensure device/emulator is connected and USB debugging is enabled

### Getting Help

1. Check test reports for detailed failure information
2. Run tests with `--info` flag for verbose output
3. Verify all dependencies are properly configured
4. Ensure Android SDK and build tools are up to date

## Future Enhancements

Potential areas for test expansion:

- End-to-end tests with real API (staging environment)
- Performance testing for large chat histories
- Accessibility testing
- Network connectivity testing
- Voice input/output testing (when implemented)
- Database migration testing (if Room is added later)
