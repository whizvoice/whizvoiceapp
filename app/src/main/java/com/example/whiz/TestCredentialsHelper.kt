package com.example.whiz

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Helper class to read test credentials from test_credentials.json file
 * instead of hardcoding them in test files
 */
object TestCredentialsHelper {
    
    data class TestCredentials(
        val email: String,
        val password: String,
        val displayName: String,
        val userId: String,
        val testAuthSecret: String
    )
    
    private var cachedCredentials: TestCredentials? = null
    
    fun getTestCredentials(context: Context? = null): TestCredentials {
        if (cachedCredentials != null) {
            return cachedCredentials!!
        }
        
        try {
            // Try to read from assets first (most reliable)
            val jsonString = try {
                context?.assets?.open("test_credentials.json")?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            } ?: run {
                // Fallback to project root file
                val projectRootFile = File("../test_credentials.json")
                if (projectRootFile.exists()) {
                    projectRootFile.readText()
                } else {
                    // Final fallback to hardcoded values
                    """
                    {
                      "google_test_account": {
                        "email": "REDACTED_TEST_EMAIL",
                        "password": "REDACTED_TEST_PASSWORD",
                        "display_name": "Test User",
                        "user_id": "test_user_123"
                      },
                      "test_environment": {
                        "test_auth_secret": "REDACTED_TEST_SECRET"
                      }
                    }
                    """.trimIndent()
                }
            }
            
            val json = JSONObject(jsonString)
            val googleAccount = json.getJSONObject("google_test_account")
            val testEnv = json.getJSONObject("test_environment")
            
            cachedCredentials = TestCredentials(
                email = googleAccount.getString("email"),
                password = googleAccount.getString("password"),
                displayName = googleAccount.getString("display_name"),
                userId = googleAccount.getString("user_id"),
                testAuthSecret = testEnv.getString("test_auth_secret")
            )
            
            return cachedCredentials!!
            
        } catch (e: Exception) {
            // Fallback to hardcoded values if file reading fails
            Log.w("TestCredentialsHelper", "Failed to read test_credentials.json, using fallback values: ${e.message}")
            
            cachedCredentials = TestCredentials(
                email = "REDACTED_TEST_EMAIL",
                password = "REDACTED_TEST_PASSWORD",
                displayName = "Test User",
                userId = "test_user_123",
                testAuthSecret = "REDACTED_TEST_SECRET"
            )
            
            return cachedCredentials!!
        }
    }
} 