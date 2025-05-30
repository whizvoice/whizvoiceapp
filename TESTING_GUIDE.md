# 🧪 WhizVoice Testing Guide

## Overview

This guide explains our testing strategy to help prevent breaking existing functionality when making changes to the Android app. It covers both the comprehensive testing strategy and the specific tests currently implemented.

## Current Test Implementation

### Test Structure

```
app/src/
├── test/                           # Unit Tests (JVM)
│   └── java/com/example/whiz/
│       ├── TestUtils.kt           # Common test utilities
│       ├── TestData.kt            # Mock data factory
│       ├── data/
│       │   ├── local/DatabaseEntitiesTest.kt
│       │   └── repository/
│       │       ├── WhizRepositoryTest.kt
│       │       └── WhizRepositoryIntegrationTest.kt
│       └── ui/viewmodels/
│           ├── AuthViewModelTest.kt
│           └── ChatsListViewModelTest.kt
└── androidTest/                   # Instrumented Tests (Android)
    └── java/com/example/whiz/ui/screens/
        ├── ChatsListScreenTest.kt  # UI tests for chat list
        ├── ChatScreenTest.kt       # UI tests for chat screen
        ├── LoginScreenTest.kt      # UI tests for login screen
        └── SettingsScreenTest.kt   # UI tests for settings screen
```

### Implemented Tests

#### Unit Tests (JVM)

**WhizRepositoryIntegrationTest** - Tests the repository layer with mocked API service:

- ✅ Chat creation and error handling
- ✅ Chat retrieval and caching
- ✅ Message operations (add user/assistant messages)
- ✅ Chat title derivation and truncation
- ✅ Incremental sync operations
- ✅ Error handling and fallback behavior
- ✅ Message count operations
- ✅ Chat persistence logic

**ViewModelTests** - Test business logic and state management:

- ✅ AuthViewModelTest - Authentication flow testing
- ✅ ChatsListViewModelTest - Chat list operations

**DatabaseEntitiesTest** - Test data transformations and entity operations

#### UI Tests (Instrumented)

**ChatsListScreenTest** - Tests the main chat list interface:

- ✅ Empty state display
- ✅ Chat list rendering
- ✅ Loading indicator
- ✅ FAB (New Chat) functionality
- ✅ Settings button navigation
- ✅ Chat item click handling

**ChatScreenTest** - Tests the individual chat interface:

- ✅ Empty conversation state
- ✅ Message display (user and assistant)
- ✅ Input field presence
- ✅ Microphone button display
- ✅ Navigation buttons (back, settings)
- ✅ Message sending functionality
- ✅ Loading states

**LoginScreenTest** - Tests the authentication interface:

- ✅ Welcome message display
- ✅ Google Sign-In button
- ✅ Loading states
- ✅ Button state management

**SettingsScreenTest** - Tests the settings interface:

- ✅ Settings title and navigation
- ✅ Voice settings section
- ✅ Speech speed and pitch sliders
- ✅ Sign out functionality
- ✅ Delete all chats functionality

## Testing Strategy

### 1. **Unit Tests** (Fast, Isolated) 🔬

- **Purpose**: Test business logic, ViewModels, repositories, data transformations
- **Location**: `app/src/test/java/`
- **Run on**: JVM (fast execution)
- **Mock**: External dependencies (API, database, Android framework)

### 2. **Integration Tests** (Medium speed) 🔗

- **Purpose**: Test how components work together
- **Examples**: Repository + API, Database operations
- **Location**: `app/src/test/java/` or `app/src/androidTest/java/`

### 3. **UI Tests** (Slower, Comprehensive) 📱

- **Purpose**: Test user interactions and UI behavior
- **Location**: `app/src/androidTest/java/`
- **Run on**: Device/Emulator
- **Tools**: Compose testing, Espresso

## Running Tests

### Quick Commands

```bash
# Run all tests with detailed reporting
./run_all_tests.sh

# Run only unit tests
./gradlew testDebugUnitTest

# Run only instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run specific test class
./gradlew testDebugUnitTest --tests="*WhizRepositoryIntegrationTest*"
./gradlew connectedAndroidTest --tests="*ChatsListScreenTest*"
```

### Alternative Test Runner

```bash
# Run all unit tests
./run_tests.sh unit

# Run specific test class
./run_tests.sh specific ChatsListViewModelTest

# Run all tests
./run_tests.sh all

# Run with coverage report
./run_tests.sh coverage
```

## Test Reports

After running tests, reports are generated at:

- **Unit Tests**: `app/build/reports/tests/testDebugUnitTest/index.html`
- **Instrumented Tests**: `app/build/reports/androidTests/connected/index.html`
- **Coverage**: `app/build/reports/coverage/testDebugUnitTestCoverage/html/index.html`

## Testing Tools We Use

### Core Testing

- **JUnit 4**: Test framework
- **Truth**: Fluent assertions (`assertThat(result).isEqualTo(expected)`)
- **Mockito**: Mocking framework
- **Turbine**: Testing Kotlin Flows
- **Coroutines Test**: Testing suspend functions

### Android-Specific

- **Hilt Testing**: Testing dependency injection
- **Room Testing**: Testing database operations
- **Compose Testing**: Testing UI components
- **Espresso**: UI testing (if needed)

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
androidTestImplementation("com.google.dagger:hilt-android-testing:2.56")
```

## Common Testing Patterns

### Testing ViewModels

```kotlin
@Test
fun `when user action triggers, should update state correctly`() = runTest {
    // Test state changes, loading states, error handling
    viewModel.someAction()

    viewModel.uiState.test {
        val state = awaitItem()
        assertThat(state.isLoading).isTrue()
        // ... more assertions
    }
}
```

### Testing Repositories

```kotlin
@Test
fun `when API call succeeds, should return transformed data`() = runTest {
    // Mock API response
    whenever(apiService.getData()).thenReturn(apiResponse)

    // Test the repository method
    val result = repository.getData()

    // Verify transformation and caching
    assertThat(result).hasSize(expectedSize)
    verify(apiService).getData()
}
```

### Testing Error Scenarios

```kotlin
@Test
fun `when network fails, should handle gracefully and not crash`() = runTest {
    // Mock failure
    whenever(apiService.getData()).thenThrow(IOException("Network error"))

    // Should not throw exception
    val result = repository.getData()

    // Should return fallback or cached data
    assertThat(result).isEmpty() // or whatever fallback behavior
}
```

## Testing Strategy for Remote-First Architecture

Since the app uses a remote-first architecture with Supabase:

- **Unit tests** mock the API service to test business logic
- **Integration tests** verify repository behavior with mocked dependencies
- **UI tests** focus on user interactions and screen states
- **No Room database tests** (since local storage is minimal)

### Mocking Strategy

- **API Service**: Fully mocked for predictable test behavior
- **SharedPreferences**: Mocked for caching tests
- **Android Framework**: Mocked using testOptions configuration

## Test Coverage Areas

### ✅ Currently Covered

- Repository business logic and error handling
- UI component rendering and interactions
- User input handling and validation
- Navigation and callback triggering
- Loading states and empty states
- Authentication flow
- Settings management
- ViewModel state management
- Data transformations

### 🔄 Future Testing Areas

- End-to-end tests with real API (staging environment)
- Performance testing for large chat histories
- Accessibility testing
- Network connectivity testing
- Voice input/output testing (when implemented)
- Database migration testing (if Room is added later)

## Best Practices

### Do ✅

- Write descriptive test names
- Test both success and failure scenarios
- Mock external dependencies
- Use `runTest` for coroutines
- Test state changes in ViewModels
- Verify mock interactions
- Keep tests focused and isolated

### Don't ❌

- Test Android framework code
- Write tests that depend on external services
- Make tests depend on each other
- Test implementation details
- Ignore test failures
- Write tests without assertions
- Mock everything (test real object interactions when possible)

## Coverage Goals

- **ViewModels**: 90%+ (critical business logic)
- **Repositories**: 85%+ (data handling)
- **Utilities**: 80%+ (helper functions)
- **Overall**: 70%+ (good safety net)

## Getting Help

1. **Look at existing tests**: See `ChatsListViewModelTest` for examples
2. **Check test utilities**: Use `TestUtils.kt` for common helpers
3. **Read documentation**: Android Testing, Mockito, Truth documentation
4. **Ask questions**: Better to ask than break production!

---

Remember: **Tests are your safety net**. They help you move fast without breaking things! 🚀
