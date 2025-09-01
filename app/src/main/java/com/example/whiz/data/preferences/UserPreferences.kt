package com.example.whiz.data.preferences

import com.example.whiz.data.api.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.auth.AuthenticationRequiredException
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import org.json.JSONObject

// Data class for voice settings
data class VoiceSettings(
    val useSystemDefaults: Boolean = false,
    val speechRate: Float = 1.25f, // 125% - faster than default (1.0)
    val pitch: Float = 0.7f // 70% - lower than default (1.0)
)

@Singleton
class UserPreferences @Inject constructor(
    private val apiService: ApiService,
    private val authRepository: AuthRepository
) {
    private val TAG = "UserPreferences"

    // Internal MutableStateFlow
    private val _hasClaudeToken = MutableStateFlow<Boolean?>(null) // Use null for initial unknown state
    private val _hasAsanaToken = MutableStateFlow<Boolean?>(null)
    
    // Voice settings state
    private val _voiceSettings = MutableStateFlow(VoiceSettings())

    // Publicly exposed StateFlow
    val hasClaudeToken: StateFlow<Boolean?> = _hasClaudeToken
    val hasAsanaToken: StateFlow<Boolean?> = _hasAsanaToken
    val voiceSettings: StateFlow<VoiceSettings> = _voiceSettings

    suspend fun initializeTokenStatus() {
        // Call this when the app starts or user logs in
        // Ensure refresh is called even if only one is null, or on explicit demand.
        // This will be triggered from SettingsViewModel init.
        Log.d(TAG, "initializeTokenStatus called. Claude: ${_hasClaudeToken.value}, Asana: ${_hasAsanaToken.value}")
        // Always attempt to refresh if there's a chance data is stale or uninitialized.
        // ViewModel will call this, and it can be called again if needed (e.g. after login)
        refreshApiTokenStatus()
        loadVoiceSettings()
    }

    suspend fun refreshApiTokenStatus() {
        try {
            // Wait for a valid server token. This will suspend until a token is available.
            val serverToken = authRepository.serverToken.first { it != null }
            Log.d(TAG, "Refreshing API token status from server with token: $serverToken")
            
            val response = apiService.getApiTokens() // Assumes getApiTokens uses the token via an interceptor
            _hasClaudeToken.value = response.has_claude_token
            _hasAsanaToken.value = response.has_asana_token
            Log.d(TAG, "Refreshed token status: Claude=${response.has_claude_token}, Asana=${response.has_asana_token}")
        } catch (e: Exception) {
            // Handle error (e.g., network error, server error)
            Log.e(TAG, "Error refreshing API token status", e)
            // Set state to false to indicate an error occurred and we don't know the status,
            // or that they are effectively not set from the app's perspective.
            _hasClaudeToken.value = false
            _hasAsanaToken.value = false
            // Check if the exception is an HttpException with a 401 status code
            if (e is HttpException && e.code() == 401) {
                Log.w(TAG, "Authentication required (401), throwing AuthenticationRequiredException.")
                throw AuthenticationRequiredException(cause = e)
            }
        }
    }


    suspend fun setClaudeToken(token: String) {
        // val serverToken = authRepository.serverToken.value // May not be needed if auth handled by interceptor
        Log.d(TAG, "Attempting to update Claude token on server via /user/api_key. New token (empty if clearing): '$token'")
        try {
            val request = ApiService.UserApiKeySetRequest(
                key_name = "claude_api_key", // Key name as expected by backend
                key_value = token.ifBlank { null } // Send null if token is blank to clear
            )
            // Log the request for debugging
            Log.d(TAG, "Sending UserApiKeySetRequest: key_name='${request.key_name}', key_value=${if (request.key_value == null) "null" else "'${request.key_value}'"}")
            val gsonWithNulls = GsonBuilder().serializeNulls().create()
            val json = gsonWithNulls.toJson(request)
            Log.d(TAG, "Request as JSON with nulls: $json")
            val response = apiService.setUserApiKey(request)
            Log.i(TAG, "Successfully sent Claude token update to server. Response: $response")
            
            // Update local state immediately from response
            _hasClaudeToken.value = response.has_claude_token
            _hasAsanaToken.value = response.has_asana_token
            Log.d(TAG, "Updated token status from response: Claude=${response.has_claude_token}, Asana=${response.has_asana_token}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Claude token on server via /user/api_key", e)
            throw e 
        }
    }

    suspend fun setAsanaToken(token: String) {
        // val serverToken = authRepository.serverToken.value // May not be needed if auth handled by interceptor
        Log.d(TAG, "Attempting to update Asana token on server via /user/api_key. New token (empty if clearing): '$token'")
        try {
            val request = ApiService.UserApiKeySetRequest(
                key_name = "asana_access_token", // Key name as expected by backend
                key_value = token.ifBlank { null } // Send null if token is blank to clear
            )
            // Log the request for debugging
            Log.d(TAG, "Sending UserApiKeySetRequest for Asana: key_name='${request.key_name}', key_value=${if (request.key_value == null) "null" else "'${request.key_value}'"}")
            val gsonWithNulls = GsonBuilder().serializeNulls().create()
            val json = gsonWithNulls.toJson(request)
            Log.d(TAG, "Asana Request as JSON with nulls: $json")
            val response = apiService.setUserApiKey(request)
            Log.i(TAG, "Successfully sent Asana token update to server. Response: $response")
            
            // Update local state immediately from response
            _hasClaudeToken.value = response.has_claude_token
            _hasAsanaToken.value = response.has_asana_token
            Log.d(TAG, "Updated token status from response: Claude=${response.has_claude_token}, Asana=${response.has_asana_token}")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Asana token on server via /user/api_key", e)
            throw e
        }
    }
    
    // Voice settings methods
    suspend fun loadVoiceSettings() {
        try {
            // Wait for a valid server token
            val serverToken = authRepository.serverToken.first { it != null }
            Log.d(TAG, "Loading voice settings from server")
            
            val response = apiService.getUserPreference("voice_settings")
            Log.d(TAG, "Voice settings response: $response")
            
            response?.let { settingsJson ->
                // Parse the JSON response to VoiceSettings
                try {
                    // The server returns a JSON string, so we need to parse it properly
                    // First, check if it's a quoted string and remove outer quotes if needed
                    val cleanJson = if (settingsJson.startsWith("\"") && settingsJson.endsWith("\"")) {
                        // Remove outer quotes and unescape the inner JSON
                        settingsJson.substring(1, settingsJson.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                    } else {
                        settingsJson
                    }
                    
                    // Use proper JSON parsing instead of regex
                    val jsonObject = JSONObject(cleanJson)
                    
                    val useSystemDefaults = jsonObject.optBoolean("useSystemDefaults", false)
                    val speechRate = jsonObject.optDouble("speechRate", 1.25).toFloat()
                    val pitch = jsonObject.optDouble("pitch", 0.7).toFloat()
                    
                    val loadedSettings = VoiceSettings(
                        useSystemDefaults = useSystemDefaults,
                        speechRate = speechRate,
                        pitch = pitch
                    )
                    
                    _voiceSettings.value = loadedSettings
                    Log.d(TAG, "Loaded voice settings: $loadedSettings")
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing voice settings JSON: $settingsJson", e)
                    // Keep default settings
                }
            } ?: run {
                Log.d(TAG, "No voice settings found on server, using defaults")
                // No settings found, use defaults
                _voiceSettings.value = VoiceSettings()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading voice settings", e)
            // Keep default settings
            _voiceSettings.value = VoiceSettings()
            if (e is HttpException && e.code() == 401) {
                throw AuthenticationRequiredException(cause = e)
            }
        }
    }
    
    suspend fun saveVoiceSettings(settings: VoiceSettings) {
        try {
            Log.d(TAG, "Saving voice settings: $settings")
            
            // Convert to JSON string (simple approach)
            val settingsJson = """
                {
                    "useSystemDefaults": ${settings.useSystemDefaults},
                    "speechRate": ${settings.speechRate},
                    "pitch": ${settings.pitch}
                }
            """.trimIndent()
            
            apiService.setUserPreference("voice_settings", settingsJson)
            _voiceSettings.value = settings
            Log.d(TAG, "Voice settings saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving voice settings", e)
            if (e is HttpException && e.code() == 401) {
                throw AuthenticationRequiredException(cause = e)
            }
            throw e
        }
    }
} 