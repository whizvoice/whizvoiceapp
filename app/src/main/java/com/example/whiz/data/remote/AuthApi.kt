package com.example.whiz.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

@Singleton
class AuthApi @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val TAG = "AuthApi"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val SERVER_URL = "https://whizvoice.com/api" // Using the domain with the API proxy
    
    /**
     * Authenticate with Google token
     */
    suspend fun authenticateWithGoogle(googleIdToken: String): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("token", googleIdToken)
            }
            
            val requestBody = jsonBody.toString().toRequestBody(JSON)
            
            val request = Request.Builder()
                .url("$SERVER_URL/auth/google")
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending authentication request to $SERVER_URL/auth/google")    
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful || responseBody == null) {
                Log.e(TAG, "Authentication failed with code: ${response.code}, message: ${response.message}")
                return@withContext Result.failure(IOException("Authentication failed: ${response.code}"))
            }
            
            Log.d(TAG, "Authentication successful, processing response")
            // Parse the response
            val jsonResponse = JSONObject(responseBody)
            val accessToken = jsonResponse.getString("access_token")
            val tokenType = jsonResponse.getString("token_type")
            val userJson = jsonResponse.getJSONObject("user")
            
            val user = User(
                id = userJson.getString("sub"),
                name = if (userJson.has("name")) userJson.getString("name") else null,
                email = if (userJson.has("email")) userJson.getString("email") else null,
                photoUrl = if (userJson.has("picture")) userJson.getString("picture") else null
            )
            
            return@withContext Result.success(
                AuthResponse(
                    accessToken = accessToken,
                    tokenType = tokenType,
                    user = user
                )
            )
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parsing error during authentication", e)
            return@withContext Result.failure(e)
        } catch (e: IOException) {
            Log.e(TAG, "Network error during authentication", e)
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during authentication", e)
            return@withContext Result.failure(e)
        }
    }
    
    /**
     * Get user profile using JWT token
     */
    suspend fun getUserProfile(token: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$SERVER_URL/me")
                .header("Authorization", "Bearer $token")
                .build()
                
            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful || responseBody == null) {
                return@withContext Result.failure(IOException("Failed to get user profile: ${response.code}"))
            }
            
            // Parse the response
            val jsonResponse = JSONObject(responseBody)
            
            val user = User(
                id = jsonResponse.getString("sub"),
                name = if (jsonResponse.has("name")) jsonResponse.getString("name") else null,
                email = if (jsonResponse.has("email")) jsonResponse.getString("email") else null,
                photoUrl = null // Not included in the /me endpoint
            )
            
            return@withContext Result.success(user)
        } catch (e: JSONException) {
            return@withContext Result.failure(e)
        } catch (e: IOException) {
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}

data class AuthResponse(
    val accessToken: String,
    val tokenType: String,
    val user: User
)

data class User(
    val id: String,
    val name: String?,
    val email: String?,
    val photoUrl: String?
) 