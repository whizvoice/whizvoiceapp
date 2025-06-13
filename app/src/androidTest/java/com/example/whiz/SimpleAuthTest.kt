package com.example.whiz

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SimpleAuthTest : BaseIntegrationTest() {

    @Test
    fun testProgrammaticAuthentication() {
        runBlocking {
            Log.e("SimpleAuthTest", "🧪 Starting simple authentication test...")
            
            // Get test credentials from helper instead of hardcoding
            val testCreds = TestCredentialsHelper.getTestCredentials()
            val testEmail = testCreds.email
            
            Log.e("SimpleAuthTest", "🧪 Calling authenticateProgrammatically for: $testEmail")
            
            try {
                val result = authRepository.authenticateProgrammatically(testEmail)
                Log.e("SimpleAuthTest", "🧪 authenticateProgrammatically result: success=${result.isSuccess}")
                
                if (result.isSuccess) {
                    Log.e("SimpleAuthTest", "🧪 ✅ Programmatic authentication SUCCESS!")
                } else {
                    Log.e("SimpleAuthTest", "🧪 ❌ Programmatic authentication FAILED: ${result.exceptionOrNull()}")
                }
            } catch (e: Exception) {
                Log.e("SimpleAuthTest", "🧪 ❌ Exception during programmatic authentication", e)
            }
            
            Log.e("SimpleAuthTest", "🧪 Simple authentication test completed")
        }
    }
} 