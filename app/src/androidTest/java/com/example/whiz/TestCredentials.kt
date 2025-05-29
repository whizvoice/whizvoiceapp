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
            val credentialsFile = File(context.filesDir.parent, "test_credentials.json")
            
            if (!credentialsFile.exists()) {
                // Try alternative locations
                val alternativeFile = File("/data/local/tmp/test_credentials.json")
                if (alternativeFile.exists()) {
                    return parseCredentialsFile(alternativeFile)
                }
                
                // Fallback to default test credentials
                return getDefaultTestCredentials()
            }
            
            parseCredentialsFile(credentialsFile)
        } catch (e: Exception) {
            android.util.Log.w("TestCredentials", "Failed to load test credentials: ${e.message}")
            getDefaultTestCredentials()
        }.also {
            _credentials = it
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
            creds.googleTestAccount.email != "test@example.com" &&
            creds.googleTestAccount.password != "test_password"
        } catch (e: Exception) {
            false
        }
    }
}

// Extension functions for easy access in tests
fun TestCredentials.isRealAuthEnabled(): Boolean = 
    testEnvironment.useRealAuth && TestCredentialsManager.hasRealCredentials()

fun TestCredentials.getTestEmail(): String = googleTestAccount.email
fun TestCredentials.getTestPassword(): String = googleTestAccount.password 