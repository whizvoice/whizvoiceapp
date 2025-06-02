package com.example.whiz

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileInputStream

data class TestCredentials(
    @SerializedName("google_test_account")
    val googleTestAccount: GoogleTestAccount,
    @SerializedName("test_environment")
    val testEnvironment: TestEnvironment
)

data class GoogleTestAccount(
    val email: String,
    val password: String,
    @SerializedName("display_name")
    val displayName: String,
    @SerializedName("user_id")
    val userId: String
)

data class TestEnvironment(
    @SerializedName("use_real_auth")
    val useRealAuth: Boolean,
    @SerializedName("api_base_url")
    val apiBaseUrl: String,
    @SerializedName("claude_api_key")
    val claudeApiKey: String,
    @SerializedName("asana_token")
    val asanaToken: String
)

object TestCredentialsManager {
    
    private var _credentials: TestCredentials? = null
    
    val credentials: TestCredentials
        get() = _credentials ?: loadCredentials()
    
    private fun loadCredentials(): TestCredentials {
        return try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            
            // Try multiple locations for test credentials
            val locations = listOf(
                File(context.filesDir.parent, "test_credentials.json"),
                File("/data/local/tmp/test_credentials.json"),
                // Check if file exists in project root via external storage
                File("/data/local/tmp/test_credentials.json")
            )
            
            for (file in locations) {
                if (file.exists()) {
                    android.util.Log.d("TestCredentials", "Found credentials at: ${file.absolutePath}")
                    return parseCredentialsFile(file)
                }
            }
            
            // Try to read from assets if available
            try {
                val inputStream = context.assets.open("test_credentials.json")
                val json = inputStream.bufferedReader().use { it.readText() }
                android.util.Log.d("TestCredentials", "Found credentials in assets")
                return Gson().fromJson(json, TestCredentials::class.java)
            } catch (e: Exception) {
                android.util.Log.d("TestCredentials", "No credentials in assets: ${e.message}")
            }
            
            android.util.Log.w("TestCredentials", "No test_credentials.json found in any location. Using defaults.")
            // Fallback to default test credentials
            getDefaultTestCredentials()
        } catch (e: Exception) {
            android.util.Log.w("TestCredentials", "Failed to load test credentials: ${e.message}")
            getDefaultTestCredentials()
        }.also {
            _credentials = it
            android.util.Log.d("TestCredentials", "Loaded credentials - useRealAuth: ${it.testEnvironment.useRealAuth}, email: ${it.googleTestAccount.email}")
        }
    }
    
    private fun parseCredentialsFile(file: File): TestCredentials {
        val json = file.readText()
        return Gson().fromJson(json, TestCredentials::class.java)
    }
    
    private fun getDefaultTestCredentials(): TestCredentials {
        return TestCredentials(
            googleTestAccount = GoogleTestAccount(
                email = "test@example.com",
                password = "test_password",
                displayName = "Test User",
                userId = "test_user_123"
            ),
            testEnvironment = TestEnvironment(
                useRealAuth = false, // Default to mock auth if no credentials
                apiBaseUrl = "https://mock-api.whizapp.com",
                claudeApiKey = "mock_claude_key",
                asanaToken = "mock_asana_token"
            )
        )
    }
    
    fun hasRealCredentials(): Boolean {
        return try {
            val creds = credentials
            val hasReal = creds.googleTestAccount.email != "test@example.com" &&
                         creds.googleTestAccount.password != "test_password"
            android.util.Log.d("TestCredentials", "hasRealCredentials: $hasReal (email: ${creds.googleTestAccount.email})")
            hasReal
        } catch (e: Exception) {
            android.util.Log.e("TestCredentials", "Error checking real credentials", e)
            false
        }
    }
}

// Extension functions for easy access in tests
fun TestCredentials.isRealAuthEnabled(): Boolean = 
    testEnvironment.useRealAuth && TestCredentialsManager.hasRealCredentials()

fun TestCredentials.getTestEmail(): String = googleTestAccount.email
fun TestCredentials.getTestPassword(): String = googleTestAccount.password 