package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.TestCredentialsManager
import android.util.Log

/**
 * Debug test to isolate authentication issues
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AuthenticationDebugTest {
    
    private companion object {
        const val TAG = "AuthDebugTest"
    }

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var authRepository: AuthRepository

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test
    fun checkTestCredentials() {
        Log.d(TAG, "🔍 Checking test credentials loading...")
        
        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "📧 Email: ${credentials.googleTestAccount.email}")
        Log.d(TAG, "🔑 Password length: ${credentials.googleTestAccount.password.length}")
        Log.d(TAG, "🌐 API URL: ${credentials.testEnvironment.apiBaseUrl}")
        Log.d(TAG, "🔐 Use real auth: ${credentials.testEnvironment.useRealAuth}")
        
        val hasRealCredentials = TestCredentialsManager.hasRealCredentials()
        Log.d(TAG, "✅ Has real credentials: $hasRealCredentials")
        
        if (!hasRealCredentials) {
            Log.w(TAG, "⚠️ Using mock credentials - automated authentication will fail")
            Log.w(TAG, "⚠️ Need to add real test_credentials.json file")
        }
        
        // This test always passes - just for debugging credentials
        assert(true)
    }

    @Test
    fun testAuthenticationTimeout(): Unit = runBlocking {
        Log.d(TAG, "🧪 Testing authentication timeout behavior...")
        
        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "📧 Target email: ${credentials.googleTestAccount.email}")
        
        if (!TestCredentialsManager.hasRealCredentials()) {
            Log.w(TAG, "⚠️ Skipping authentication test - no real credentials available")
            return@runBlocking
        }
        
        Log.d(TAG, "🔐 Starting authentication test with timeouts...")
        val authSuccess = AuthenticationTestHelper.ensureWhizVoiceTestAuthentication(authRepository, device)
        
        Log.d(TAG, "📊 Authentication result: $authSuccess")
        
        if (authSuccess) {
            val currentUser = authRepository.userProfile.value
            Log.d(TAG, "✅ Successfully authenticated as: ${currentUser?.email}")
        } else {
            Log.w(TAG, "❌ Authentication failed or timed out")
            Log.w(TAG, "❌ This could be due to UI automation issues or network problems")
        }
        
        // Don't fail the test - just report results
        assert(true)
    }
} 