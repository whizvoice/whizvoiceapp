package com.example.whiz.di

import android.content.Context
import androidx.room.Room
import com.example.whiz.data.local.WhizDatabase
import com.example.whiz.data.local.ChatDao
import com.example.whiz.data.local.MessageDao
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager
import com.example.whiz.data.remote.WhizServerRepository
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.api.SupabaseApi
import com.example.whiz.data.auth.TokenAuthenticator
import com.example.whiz.data.preferences.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import javax.inject.Provider
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Test module that provides all dependencies like production but with mock services for testing
 */

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule {

    @Provides
    @Singleton
    fun provideTestDatabase(@ApplicationContext context: Context): WhizDatabase {
        return Room.inMemoryDatabaseBuilder(
            context,
            WhizDatabase::class.java
        ).allowMainThreadQueries() // Allow main thread queries for testing
            .build()
    }

    @Provides
    @Singleton
    fun provideChatDao(database: WhizDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: WhizDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideWhizRepository(
        apiService: ApiService,
        @ApplicationContext context: Context
    ): WhizRepository {
        return WhizRepository(apiService, context)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authRepositoryProvider: Provider<AuthRepository>,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .authenticator(tokenAuthenticator)
            .addInterceptor(Interceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                
                // Skip adding Authorization header for authentication endpoints
                val isAuthRequest = originalRequest.url.pathSegments.contains("auth")
                
                if (!isAuthRequest) {
                    // Get AuthRepository instance via provider
                    val authRepository = authRepositoryProvider.get()
                    // Get token asynchronously but don't block if it's null
                    val token: String? = runBlocking { 
                        authRepository.serverToken.first() // Get current token, could be null
                    }

                    if (token != null) {
                        requestBuilder.header("Authorization", "Bearer $token")
                    }
                }
                
                chain.proceed(requestBuilder.build())
            })
            .readTimeout(30, TimeUnit.SECONDS) // Adjust timeouts as needed
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideWhizServerRepository(
        okHttpClient: OkHttpClient,
        authRepository: AuthRepository
    ): WhizServerRepository {
        return WhizServerRepository(okHttpClient, authRepository)
    }
    
    @Provides
    @Singleton
    fun provideAuthRepository(
        @ApplicationContext context: Context,
        authApi: AuthApi
    ): AuthRepository {
        return AuthRepository(context, authApi)
    }
    
    @Provides
    @Singleton
    fun provideAuthApi(okHttpClient: OkHttpClient): AuthApi {
        return AuthApi(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideApiService(): ApiService {
        return TestApiService()
    }

    @Provides
    @Singleton
    fun provideSupabaseApi(): SupabaseApi {
        return TestSupabaseApi()
    }

    @Provides
    @Singleton
    fun provideSpeechRecognitionService(@ApplicationContext context: Context): SpeechRecognitionService {
        return SpeechRecognitionService(context)
    }

    @Provides
    @Singleton
    fun provideTTSManager(@ApplicationContext context: Context): TTSManager {
        return TTSManager(context)
    }

    @Provides
    @Singleton
    fun provideTokenAuthenticator(
        authRepositoryProvider: Provider<AuthRepository>,
        authApiProvider: Provider<AuthApi>
    ): TokenAuthenticator {
        return TokenAuthenticator(authRepositoryProvider, authApiProvider)
    }

    @Provides
    @Singleton
    fun provideUserPreferences(
        apiService: ApiService,
        authRepository: AuthRepository
    ): UserPreferences {
        return UserPreferences(apiService, authRepository)
    }
}

/**
 * Mock implementations for testing - these return empty/success responses
 */
class TestApiService : ApiService {
    override suspend fun getConversationsIncremental(since: String?): ApiService.ConversationsResponse {
        return ApiService.ConversationsResponse(
            conversations = emptyList(),
            server_timestamp = "2024-01-01T00:00:00Z",
            is_incremental = true,
            count = 0
        )
    }

    override suspend fun getConversations(): List<ApiService.ConversationResponse> {
        return emptyList()
    }

    override suspend fun getConversation(id: Long): ApiService.ConversationResponse {
        return ApiService.ConversationResponse(
            id = id,
            user_id = "test_user",
            title = "Test Chat",
            created_at = "2024-01-01T00:00:00Z",
            last_message_time = "2024-01-01T00:00:00Z",
            source = "test",
            google_session_id = null
        )
    }

    override suspend fun createConversation(request: ApiService.ConversationCreate): ApiService.ConversationResponse {
        return ApiService.ConversationResponse(
            id = 1L,
            user_id = "test_user",
            title = request.title,
            created_at = "2024-01-01T00:00:00Z",
            last_message_time = "2024-01-01T00:00:00Z",
            source = request.source,
            google_session_id = request.google_session_id
        )
    }

    override suspend fun updateConversation(id: Long, request: ApiService.ConversationUpdate): ApiService.ConversationResponse {
        return ApiService.ConversationResponse(
            id = id,
            user_id = "test_user",
            title = request.title ?: "Updated Chat",
            created_at = "2024-01-01T00:00:00Z",
            last_message_time = "2024-01-01T00:00:00Z",
            source = "test",
            google_session_id = null
        )
    }

    override suspend fun deleteAllConversations(): Map<String, String> {
        return mapOf("message" to "All conversations deleted")
    }

    override suspend fun updateConversationLastMessageTime(id: Long): Map<String, String> {
        return mapOf("message" to "Updated")
    }

    override suspend fun getMessages(conversationId: Long): List<ApiService.MessageResponse> {
        return emptyList()
    }

    override suspend fun getMessagesIncremental(conversationId: Long, since: String?, limit: Int): ApiService.MessagesResponse {
        return ApiService.MessagesResponse(
            messages = emptyList(),
            server_timestamp = "2024-01-01T00:00:00Z",
            is_incremental = true,
            count = 0,
            conversation_id = conversationId,
            has_more = false
        )
    }

    override suspend fun createMessage(request: ApiService.MessageCreate): ApiService.MessageResponse {
        return ApiService.MessageResponse(
            id = 1L,
            conversation_id = request.conversation_id,
            content = request.content,
            message_type = request.message_type,
            timestamp = "2024-01-01T00:00:00Z"
        )
    }

    override suspend fun getMessageCount(conversationId: Long): ApiService.MessageCountResponse {
        return ApiService.MessageCountResponse(count = 0)
    }

    override suspend fun getApiTokens(): ApiService.TokenResponse {
        return ApiService.TokenResponse(
            has_claude_token = false,
            has_asana_token = false
        )
    }

    override suspend fun updateApiTokens(request: ApiService.TokenUpdateRequest): Map<String, String> {
        return mapOf("message" to "Tokens updated")
    }

    override suspend fun setUserApiKey(request: ApiService.UserApiKeySetRequest): Map<String, String> {
        return mapOf("message" to "API key set")
    }

    override suspend fun getUserPreference(key: String): String? {
        return null // Return null for test
    }

    override suspend fun setUserPreference(key: String, value: String): Map<String, String> {
        return mapOf("message" to "Preference set")
    }
}

class TestSupabaseApi : SupabaseApi {
    override suspend fun getEncryptedPreference(key: String): String? {
        return null // Return null for test
    }

    override suspend fun setEncryptedPreference(
        userId: String,
        preferences: Map<String, Any>,
        encryptionKey: String
    ): Boolean {
        return true // Return success for test
    }
} 