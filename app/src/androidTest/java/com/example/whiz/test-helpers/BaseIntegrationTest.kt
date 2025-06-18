package com.example.whiz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import javax.inject.Inject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Base class for integration tests that need authentication.
 * Automatically handles test authentication setup.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
abstract class BaseIntegrationTest {
    
    @get:Rule
    var hiltRule = HiltAndroidRule(this)
    
    @Inject
    lateinit var authRepository: AuthRepository
    
    protected lateinit var device: UiDevice
    protected lateinit var context: Context
    protected val packageName = "com.example.whiz.debug"
    private val screenshotDir = "/sdcard/Download/test_screenshots"
    
    /**
     * Override this to skip automatic authentication (e.g., for login UI tests)
     */
    protected open val skipAutoAuthentication: Boolean = false
    
    @Before
    open fun setUpAuthentication() {
        hiltRule.inject()
        
        // Initialize UiDevice and Context for all tests
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = InstrumentationRegistry.getInstrumentation().context
        
        // Set up screenshot directory
        setupScreenshotDirectory()
        
        if (!skipAutoAuthentication) {
            runBlocking {
                try {
                    val authSuccess = AutoTestAuthentication.ensureAuthenticated(authRepository)
                    if (!authSuccess) {
                        throw AssertionError(
                            "❌ Test authentication failed. " +
                            "Please ensure REDACTED_TEST_EMAIL is signed in to the device and app."
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BaseIntegrationTest", "Authentication setup failed", e)
                    throw e
                }
            }
        }
    }
    
    /**
     * Set up screenshot directory - clear existing screenshots and create fresh directory
     */
    private fun setupScreenshotDirectory() {
        try {
            // Clear existing screenshots on device
            device.executeShellCommand("rm -rf $screenshotDir")
            // Create fresh directory on device
            device.executeShellCommand("mkdir -p $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "🔧 Device screenshot directory prepared: $screenshotDir")
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "Failed to setup screenshot directory", e)
        }
    }
    

    

    
    /**
     * Take a screenshot for test failure debugging
     * @param testName Name of the test that failed
     * @param reason Brief description of the failure
     */
    protected fun takeFailureScreenshot(testName: String, reason: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val filename = "${testName}_${timestamp}.png"
            val filepath = "$screenshotDir/$filename"
            
            android.util.Log.d("BaseIntegrationTest", "🔍 Taking failure screenshot: $reason")
            device.takeScreenshot(File(filepath))
            
            // Screenshot will be pulled to local folder by test script after completion
            
            // Also dump UI hierarchy for debugging
            val allElements = device.findObjects(By.pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 UI Dump for $testName:")
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${allElements.size} elements in package $packageName")
            
            val clickableElements = device.findObjects(By.clickable(true).pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${clickableElements.size} clickable elements")
            clickableElements.forEachIndexed { index, element ->
                try {
                    val text = element.text ?: "no text"
                    val desc = element.contentDescription ?: "no desc" 
                    val className = element.className ?: "no class"
                    android.util.Log.d("BaseIntegrationTest", "🔍 Element $index: text='$text', desc='$desc', class='$className'")
                } catch (e: Exception) {
                    android.util.Log.d("BaseIntegrationTest", "🔍 Element $index: error reading properties")
                }
            }
            
            android.util.Log.d("BaseIntegrationTest", "✅ Screenshot saved: $filepath")
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "Failed to take failure screenshot", e)
        }
    }
    
    /**
     * Get the current test method name from the call stack
     */
    private fun getCurrentTestMethodName(): String {
        return try {
            val stackTrace = Thread.currentThread().stackTrace
            // Look for test method (starts with "test" or has @Test annotation)
            val testMethod = stackTrace.find { 
                it.methodName.startsWith("test") || 
                it.methodName.contains("_")  // Common pattern like "testSomething_shouldDoSomething"
            }
            testMethod?.methodName ?: "unknownTest"
        } catch (e: Exception) {
            "unknownTest"
        }
    }
    
    /**
     * Custom fail method that automatically takes a screenshot before failing
     * @param message The failure message
     */
    protected fun failWithScreenshot(message: String): Nothing {
        val testName = getCurrentTestMethodName()
        android.util.Log.d("BaseIntegrationTest", "🔴 FAIL WITH SCREENSHOT: $message")
        takeFailureScreenshot(testName, message)
        org.junit.Assert.fail(message)
        throw AssertionError(message) // This will never be reached but satisfies Nothing return type
    }
    
    /**
     * Custom fail method with reason parameter for clearer screenshot naming
     * @param message The failure message  
     * @param reason Brief description for screenshot filename
     */
    protected fun failWithScreenshot(message: String, reason: String): Nothing {
        val testName = getCurrentTestMethodName()
        android.util.Log.d("BaseIntegrationTest", "🔴 FAIL WITH SCREENSHOT (reason: $reason): $message")
        takeFailureScreenshot(testName, reason)
        org.junit.Assert.fail(message)
        throw AssertionError(message) // This will never be reached but satisfies Nothing return type
    }
}

/**
 * Base class for integration tests that specifically test login/authentication UI.
 * Skips automatic authentication setup.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
abstract class BaseLoginTest : BaseIntegrationTest() {
    override val skipAutoAuthentication: Boolean = true
} 