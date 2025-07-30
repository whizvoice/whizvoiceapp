package com.example.whiz.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.Query
import android.util.Log

interface ApiService {
    data class TokenUpdateRequest(
        val claude_api_key: String? = null,
        val asana_access_token: String? = null
    )

    data class UserApiKeySetRequest(
        val key_name: String,
        val key_value: String?
    )

    data class TokenResponse(
        val has_claude_token: Boolean,
        val has_asana_token: Boolean
    )

    // ========== CONVERSATION API MODELS ==========
    data class ConversationCreate(
        val title: String,
        val source: String = "app",
        val google_session_id: String? = null
    )

    data class ConversationUpdate(
        val title: String? = null
    )

    data class ConversationResponse(
        val id: Long,
        val user_id: String,
        val title: String,
        val created_at: String,
        val last_message_time: String,
        val source: String,
        val google_session_id: String? = null,
        val deleted_at: String? = null
    )

    data class MessageCreate(
        val conversation_id: Long,
        val content: String,
        val message_type: String,  // 'USER' or 'ASSISTANT'
        val request_id: String? = null  // Client-generated UUID for request tracking
    )

    data class MessageResponse(
        val id: Long,
        val conversation_id: Long,
        val content: String,
        val message_type: String,
        val timestamp: String,
        val request_id: String? = null  // Request ID for tracking request/response pairs
    )

    data class MessageCountResponse(
        val count: Int
    )

    // ========== INCREMENTAL SYNC MODELS ==========
    data class ConversationsResponse(
        val conversations: List<ConversationResponse>,
        val server_timestamp: String,
        val is_incremental: Boolean,
        val count: Int
    )

    data class MessagesResponse(
        val messages: List<MessageResponse>,
        val conversation_id: Long,
        val server_timestamp: String,
        val is_incremental: Boolean,
        val count: Int,
        val has_more: Boolean
    )

    // ========== EXISTING TOKEN ENDPOINTS ==========
    @GET("/api/preferences/tokens")
    suspend fun getApiTokens(): TokenResponse

    @POST("/api/preferences/tokens")
    suspend fun updateApiTokens(@Body request: TokenUpdateRequest): Map<String, String>

    @POST("/api/user/api_key")
    suspend fun setUserApiKey(@Body request: UserApiKeySetRequest): Map<String, String>

    // ========== CONVERSATION ENDPOINTS ==========
    @GET("/api/conversations")
    suspend fun getConversations(): List<ConversationResponse>

    @GET("/api/conversations")
    suspend fun getConversationsIncremental(
        @Query("since") since: String? = null
    ): ConversationsResponse

    @POST("/api/conversations")
    suspend fun createConversation(@Body request: ConversationCreate): ConversationResponse

    @GET("/api/conversations/{id}")
    suspend fun getConversation(@Path("id") id: Long): ConversationResponse

    @PUT("/api/conversations/{id}")
    suspend fun updateConversation(@Path("id") id: Long, @Body request: ConversationUpdate): ConversationResponse

    @DELETE("/api/conversations")
    suspend fun deleteAllConversations(): Map<String, String>

    @DELETE("/api/conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: Long): Map<String, String>

    @PUT("/api/conversations/{id}/last_message_time")
    suspend fun updateConversationLastMessageTime(@Path("id") id: Long): Map<String, String>

    // ========== MESSAGE ENDPOINTS ==========
    @GET("/api/conversations/{id}/messages")
    suspend fun getMessages(@Path("id") conversationId: Long): List<MessageResponse>

    @GET("/api/conversations/{id}/messages")
    suspend fun getMessagesIncremental(
        @Path("id") conversationId: Long,
        @Query("since") since: String? = null,
        @Query("limit") limit: Int = 100
    ): MessagesResponse

    @POST("/api/messages")
    suspend fun createMessage(@Body request: MessageCreate): MessageResponse

    @GET("/api/conversations/{id}/messages/count")
    suspend fun getMessageCount(@Path("id") conversationId: Long): MessageCountResponse

    // ========== USER PREFERENCE ENDPOINTS ==========
    @GET("/api/user/preference")
    suspend fun getUserPreference(@Query("key") key: String): String?
    
    @POST("/api/user/preference")
    suspend fun setUserPreference(@Query("key") key: String, @Body value: String): Map<String, String>
} 