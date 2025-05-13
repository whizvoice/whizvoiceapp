package com.example.whiz.data.api

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface SupabaseApi {
    @GET("user_preferences")
    suspend fun getEncryptedPreference(@Query("key") key: String): String?

    @POST("rpc/insert_user_preferences")
    suspend fun setEncryptedPreference(
        @Query("p_user_id") userId: String,
        @Query("p_preferences") preferences: Map<String, Any>,
        @Query("p_encryption_key") encryptionKey: String
    ): Boolean
} 