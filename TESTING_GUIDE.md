# 🧪 WhizVoice Testing Guide

## Overview

This guide explains our testing strategy to help prevent breaking existing functionality when making changes to the Android app.

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

## What Should Be Tested

### High Priority ⚡

#### ViewModels

- **Why**: Contains business logic, most likely to break
- **Test**: State changes, error handling, loading states, user actions
- **Example**: `ChatsListViewModelTest`, `AuthViewModelTest`

#### Repository Layer

- **Why**: Handles data operations and caching
- **Test**: API calls, error handling, data transformation, caching logic
- **Example**: `WhizRepositoryTest`

#### Data Transformations

- **Why**: Data mapping errors can cause crashes
- **Test**: Entity conversions, API response parsing
- **Example**: `DatabaseEntitiesTest`

### Medium Priority 🔄

#### Authentication Logic

- **Why**: Critical for app functionality
- **Test**: Sign-in flow, token management, error states

#### Network Layer

- **Why**: API changes can break the app
- **Test**: Request/response handling, error scenarios

#### Database Operations

- **Why**: Data corruption can cause crashes
- **Test**: CRUD operations, migrations, constraints

### Lower Priority (but still important) 📝

#### Utility Functions

- **Test**: Date formatting, string manipulation, calculations

#### UI Components

- **Test**: User interactions, navigation, state display

## Running Tests

### Quick Commands

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

### Manual Gradle Commands

```bash
# Unit tests only
./gradlew test

# Specific test class
./gradlew test --tests "com.example.whiz.ui.viewmodels.ChatsListViewModelTest"

# Specific test method
./gradlew test --tests "ChatsListViewModelTest.createNewChat should return chat ID from repository"

# Integration tests (requires device/emulator)
./gradlew connectedAndroidTest

# Generate coverage report
./gradlew testDebugUnitTestCoverage
```

## Test Structure

### Good Test Anatomy

```kotlin
@Test
fun `descriptive test name explaining what should happen`() = runTest {
    // Given - Setup test data and mocks
    val expectedResult = "expected value"
    whenever(mockDependency.method()).thenReturn(expectedResult)

    // When - Execute the action being tested
    val result = systemUnderTest.performAction()

    // Then - Verify the outcome
    assertThat(result).isEqualTo(expectedResult)
    verify(mockDependency).method()
}
```

### Test Naming Convention

Use descriptive names that explain:

- **What** is being tested
- **When** (under what conditions)
- **Should** (expected outcome)

Examples:

- `when user creates new chat, should return chat ID from repository`
- `when API fails, should return cached data and not crash`
- `when sign in succeeds, should save tokens and update auth state`

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

## When to Write Tests

### Before Making Changes

1. **Identify affected components**: What ViewModels, repositories, or data classes will change?
2. **Write tests for current behavior**: Ensure existing functionality is captured
3. **Make your changes**: Implement the new feature or fix
4. **Update tests**: Modify tests to reflect new expected behavior
5. **Run all tests**: Ensure nothing else broke

### Red-Green-Refactor (TDD)

1. **Red**: Write a failing test for new functionality
2. **Green**: Write minimal code to make the test pass
3. **Refactor**: Improve the code while keeping tests green

## Debugging Test Failures

### Common Issues

1. **Async/Coroutine Issues**

   - Use `runTest` for suspend functions
   - Use `MainDispatcherRule` for ViewModels
   - Use `Turbine` for testing Flows

2. **Mock Setup Issues**

   - Ensure all dependencies are mocked
   - Use `whenever()` to set up mock behavior
   - Verify mock interactions with `verify()`

3. **Hilt/DI Issues**
   - Use `@HiltAndroidTest` for integration tests
   - Create test modules for mocked dependencies

### Test Reports

After running tests, check reports at:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/coverage/testDebugUnitTestCoverage/html/index.html`

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
