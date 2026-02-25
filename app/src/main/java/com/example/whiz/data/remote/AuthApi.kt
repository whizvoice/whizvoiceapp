package com.example.whiz.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import com.example.whiz.data.auth.RefreshTokenRequest
import com.example.whiz.data.auth.NewAccessTokenResponse
import com.example.whiz.TestCredentialsHelper
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

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
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Raw /auth/google response body: $responseBody")

                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Authentication failed with code: ${response.code}, message: ${response.message}")
                    return@withContext Result.failure(IOException("Authentication failed: ${response.code}"))
                }

                Log.d(TAG, "Authentication successful, processing response")
                // Parse the response
                val jsonResponse = JSONObject(responseBody)
                val accessToken = jsonResponse.getString("access_token")
                val tokenType = jsonResponse.getString("token_type")
                val refreshToken = jsonResponse.getString("refresh_token")
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
                        refreshToken = refreshToken,
                        user = user
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during authenticateWithGoogle", e)
            return@withContext Result.failure(e)
        }
    }
    
    /**
     * Test authentication that bypasses Google OAuth
     * Uses HTTP Basic Auth for security
     */
    suspend fun authenticateWithTestCredentials(
        email: String,
        userId: String,
        name: String = "Test User"
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            // Get test credentials from helper
            val testCreds = TestCredentialsHelper.getTestCredentials()
            
            val jsonBody = JSONObject().apply {
                put("email", email)
                put("user_id", userId)
                put("name", name)
            }
            
            val requestBody = jsonBody.toString().toRequestBody(JSON)
            
            // Create basic auth header
            val credentials = "${testCreds.email}:${testCreds.password}"
            val basicAuth = "Basic " + android.util.Base64.encodeToString(
                credentials.toByteArray(), 
                android.util.Base64.NO_WRAP
            )
            
            val request = Request.Builder()
                .url("$SERVER_URL/auth/test")
                .header("Authorization", basicAuth)
                .post(requestBody)
                .build()
            
            Log.d(TAG, "Sending test authentication request to $SERVER_URL/auth/test")
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Raw /auth/test response body: $responseBody")

                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Test authentication failed with code: ${response.code}, message: ${response.message}")
                    return@withContext Result.failure(IOException("Test authentication failed: ${response.code}"))
                }

                Log.d(TAG, "Test authentication successful, processing response")
                // Parse the response (same format as Google auth)
                val jsonResponse = JSONObject(responseBody)
                val accessToken = jsonResponse.getString("access_token")
                val tokenType = jsonResponse.getString("token_type")
                val refreshToken = jsonResponse.getString("refresh_token")
                val userJson = jsonResponse.getJSONObject("user")

                val user = User(
                    id = userJson.getString("sub"),
                    name = if (userJson.has("name") && !userJson.isNull("name")) userJson.getString("name") else null,
                    email = if (userJson.has("email")) userJson.getString("email") else null,
                    photoUrl = if (userJson.has("picture") && !userJson.isNull("picture")) userJson.getString("picture") else null
                )

                return@withContext Result.success(
                    AuthResponse(
                        accessToken = accessToken,
                        tokenType = tokenType,
                        refreshToken = refreshToken,
                        user = user
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during test authentication", e)
            return@withContext Result.failure(e)
        }
    }
    
    /**
     * Refresh the access token using a refresh token.
     */
    suspend fun refreshAccessToken(requestPayload: RefreshTokenRequest): NewAccessTokenResponse = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("refresh_token", requestPayload.refresh_token)
            }
            val requestBody = jsonBody.toString().toRequestBody(JSON)

            val request = Request.Builder()
                .url("$SERVER_URL/auth/refresh") // Ensure this path is correct
                .post(requestBody)
                .build()

            Log.d(TAG, "Sending refresh token request to $SERVER_URL/auth/refresh")
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Raw /auth/refresh response body: $responseBody")

                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Refresh token failed with code: ${response.code}, message: ${response.message}")
                    throw IOException("Refresh token failed: ${response.code} - ${response.message}")
                }

                Log.d(TAG, "Refresh token successful, processing response")
                val jsonResponse = JSONObject(responseBody)
                val newAccessToken = jsonResponse.getString("access_token")
                val tokenType = jsonResponse.optString("token_type", "bearer") // Default to bearer if not present

                NewAccessTokenResponse(
                    access_token = newAccessToken,
                    token_type = tokenType
                )
            }
        } catch (e: JSONException) {
            Log.e(TAG, "JSONException during refreshAccessToken", e)
            throw IOException("Failed to parse refresh token response", e) // Propagate as IOException or custom
        } catch (e: IOException) {
            Log.e(TAG, "IOException during refreshAccessToken", e)
            throw e // Re-throw original IOException
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception during refreshAccessToken", e)
            throw IOException("Unexpected error during token refresh", e) // Propagate as IOException or custom
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
                
            okHttpClient.newCall(request).execute().use { response ->
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
            }
        } catch (e: JSONException) {
            return@withContext Result.failure(e)
        } catch (e: IOException) {
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    data class SetTimezoneRequest(val timezone: String)

    @POST("user/timezone") // This annotation is informational if not using Retrofit to generate this class
    suspend fun setUserTimezone(@Body requestBody: SetTimezoneRequest): Response<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject()
            json.put("timezone", requestBody.timezone)
            val body = json.toString().toRequestBody(JSON)

            // Assuming an OkHttp Interceptor will add the Authorization header
            val request = Request.Builder()
                .url("$SERVER_URL/user/timezone")
                // .header("Authorization", "Bearer $serverToken") // Removed: Interceptor should handle this
                .post(body)
                .build()

            Log.d(TAG, "Sending setUserTimezone request to $SERVER_URL/user/timezone")
            okHttpClient.newCall(request).execute().use { okHttpResponse ->
                if (okHttpResponse.isSuccessful) {
                    Response.success(Unit, okHttpResponse.headers)
                } else {
                    val errorBody = okHttpResponse.body?.string() ?: ""
                    Response.error(okHttpResponse.code, errorBody.toResponseBody(null))
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "JSONException in setUserTimezone", e)
            throw IOException("Failed to create JSON for setUserTimezone", e)
        } catch (e: IOException) {
            Log.e(TAG, "IOException in setUserTimezone", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception in setUserTimezone", e)
            throw IOException("Unexpected error in setUserTimezone", e)
        }
    }
}

data class AuthResponse(
    val accessToken: String,
    val tokenType: String,
    val refreshToken: String,
    val user: User
)

data class User(
    val id: String,
    val name: String?,
    val email: String?,
    val photoUrl: String?
) 