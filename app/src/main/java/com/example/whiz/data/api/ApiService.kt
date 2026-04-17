package com.example.whiz.data.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import android.util.Log
import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody

interface ApiService {
    data class TokenUpdateRequest(
        val claude_api_key: String? = null,
        val asana_access_token: String? = null
    )

    data class UserApiKeySetRequest(
        @SerializedName("key_name") val key_name: String,
        @SerializedName("key_value") val key_value: String? = null  // Add default value
    )

    data class TokenResponse(
        val has_claude_token: Boolean,
        val has_asana_token: Boolean
    )
    
    data class ApiKeyUpdateResponse(
        val message: String,
        val has_claude_token: Boolean,
        val has_asana_token: Boolean,
        val cleared: Boolean? = null
    )

    // ========== CONVERSATION API MODELS ==========
    data class ConversationCreate(
        val title: String,
        val source: String = "app",
        val google_session_id: String? = null,
        val optimistic_chat_id: String? = null
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
        val deleted_at: String? = null,
        val optimistic_chat_id: String? = null
    )

    data class MessageCreate(
        val conversation_id: Long,
        val content: String,
        val message_type: String,  // 'USER' or 'ASSISTANT'
        val request_id: String? = null,  // Client-generated UUID for request tracking
        val timestamp: String? = null  // Optional timestamp in ISO format for preserving message order
    )

    data class MessageResponse(
        val id: Long,
        val conversation_id: Long,
        val content: String,
        val message_type: String,  // API returns message_type (server maps message_sender -> message_type)
        val timestamp: String,
        val request_id: String? = null,  // Request ID for tracking request/response pairs
        val cancelled: String? = null  // Timestamp when message was cancelled (null if not cancelled)
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
    suspend fun setUserApiKey(@Body request: UserApiKeySetRequest): ApiKeyUpdateResponse

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
    
    // ========== SUBSCRIPTION ENDPOINTS ==========
    data class CreateCheckoutSessionRequest(
        val success_url: String,
        val cancel_url: String
    )
    
    data class CreateCheckoutSessionResponse(
        val checkout_url: String,
        val session_id: String
    )
    
    data class CancelSubscriptionResponse(
        val status: String,
        val message: String,
        val canceled_at: Long? = null
    )
    
    @GET("/api/subscription/status")
    suspend fun getSubscriptionStatus(): com.example.whiz.data.local.SubscriptionStatus
    
    @POST("/api/subscription/create-checkout-session")
    suspend fun createCheckoutSession(@Body request: CreateCheckoutSessionRequest): CreateCheckoutSessionResponse
    
    @POST("/api/subscription/cancel")
    suspend fun cancelSubscription(): CancelSubscriptionResponse

    // ========== PENDING REQUESTS ENDPOINTS ==========
    data class PendingRequestsResponse(
        @SerializedName("has_pending") val hasPending: Boolean,
        @SerializedName("request_ids") val requestIds: List<String>
    )

    @GET("/api/conversations/{id}/pending-requests")
    suspend fun getPendingRequests(@Path("id") conversationId: Long): PendingRequestsResponse

    // ========== UI DUMP ENDPOINTS ==========
    data class UiDumpCreate(
        @SerializedName("dump_reason") val dumpReason: String,
        @SerializedName("error_message") val errorMessage: String? = null,
        @SerializedName("ui_hierarchy") val uiHierarchy: String? = null,
        @SerializedName("package_name") val packageName: String? = null,
        @SerializedName("device_model") val deviceModel: String? = null,
        @SerializedName("device_manufacturer") val deviceManufacturer: String? = null,
        @SerializedName("android_version") val androidVersion: String? = null,
        @SerializedName("screen_width") val screenWidth: Int? = null,
        @SerializedName("screen_height") val screenHeight: Int? = null,
        @SerializedName("app_version") val appVersion: String? = null,
        @SerializedName("conversation_id") val conversationId: Long? = null,
        @SerializedName("recent_actions") val recentActions: List<String>? = null,
        @SerializedName("screen_agent_context") val screenAgentContext: Map<String, Any>? = null,
        @SerializedName("is_emulator") val isEmulator: Boolean? = null
    )

    data class UiDumpResponse(
        val id: Long,
        @SerializedName("created_at") val createdAt: String
    )

    @POST("/api/ui-dumps")
    suspend fun uploadUiDump(@Body request: UiDumpCreate): UiDumpResponse

    // ========== WAKE WORD AUDIO ENDPOINTS ==========
    data class WakeWordAudioResponse(
        val id: Long,
        @SerializedName("created_at") val createdAt: String
    )

    @Multipart
    @POST("/api/wake-word-audio")
    suspend fun uploadWakeWordAudio(
        @Part file: MultipartBody.Part,
        @Part("phrase") phrase: RequestBody,
        @Part("confidence") confidence: RequestBody,
        @Part("accepted") accepted: RequestBody,
        @Part("timestamp") timestamp: RequestBody,
        @Part("raw_vosk_json") rawVoskJson: RequestBody,
        @Part("classifier_score") classifierScore: RequestBody
    ): WakeWordAudioResponse
} 