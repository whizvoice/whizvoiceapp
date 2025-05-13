package com.example.whiz.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import android.util.Log

interface ApiService {
    data class TokenUpdateRequest(
        val claude_api_key: String? = null,
        val asana_access_token: String? = null
    )

    data class TokenResponse(
        val has_claude_token: Boolean,
        val has_asana_token: Boolean
    )

    @GET("/api/preferences/tokens")
    suspend fun getApiTokens(): TokenResponse

    @POST("/api/preferences/tokens")
    suspend fun updateApiTokens(@Body request: TokenUpdateRequest): Map<String, String>
} 