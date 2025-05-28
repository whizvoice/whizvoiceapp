package com.example.whiz

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Test utilities and common testing helpers
 */

/**
 * Rule that swaps the background executor used by the Architecture Components with a different
 * one which executes each task synchronously.
 */
class MainDispatcherRule @OptIn(ExperimentalCoroutinesApi::class) constructor(
    private val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestRule {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                Dispatchers.setMain(testDispatcher)
                try {
                    base.evaluate()
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }
    }
}

/**
 * Extension function to create InstantTaskExecutorRule for easier testing
 */
fun createInstantTaskExecutorRule(): InstantTaskExecutorRule = InstantTaskExecutorRule()

/**
 * Common test utilities for mocking and assertions
 */
object TestUtils {
    
    /**
     * Creates a test exception with a standard message
     */
    fun createTestException(message: String = "Test exception"): Exception {
        return RuntimeException(message)
    }
    
    /**
     * Creates a test network exception
     */
    fun createNetworkException(): Exception {
        return java.io.IOException("Network error")
    }
}

/**
 * Extension functions for testing
 */
fun String.toTestUserId(): String = "test_$this" 