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
                // Try /data/local/tmp (pushed by test script via adb push)
                val adbPushedFile = File("/data/local/tmp/test_credentials.json")
                // Fallback to project root file
                val projectRootFile = File("../test_credentials.json")
                if (adbPushedFile.exists()) {
                    adbPushedFile.readText()
                } else if (projectRootFile.exists()) {
                    projectRootFile.readText()
                } else {
                    throw IllegalStateException(
                        "test_credentials.json not found. Copy test_credentials.json.example " +
                        "to test_credentials.json and fill in your test credentials."
                    )
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
            )
            
            return cachedCredentials!!
            
        } catch (e: Exception) {
            Log.e("TestCredentialsHelper", "Failed to read test_credentials.json: ${e.message}")
            throw IllegalStateException(
                "test_credentials.json not found or invalid. Copy test_credentials.json.example " +
                "to test_credentials.json and fill in your test credentials.",
                e
            )
        }
    }
} 