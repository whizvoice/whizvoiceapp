package com.example.whiz.di

import android.content.Context
import android.util.Log
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
import com.example.whiz.di.AppModule
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
import dagger.hilt.InstallIn
import javax.inject.Named

/**
 * Test module that provides all dependencies like production but with mock services for testing
 */

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule {

    private const val TAG = "TestAppModule"

    init {
        Log.w(TAG, "🚀 TestAppModule object is being initialized! This means Hilt is using our test module.")
    }

    @Provides
    @Singleton
    fun provideDebugString(): String {
        Log.w(TAG, "🚀 provideDebugString called - TestAppModule is definitely being used!")
        return "TEST_MODULE_ACTIVE"
    }

    @Provides
    @Singleton
    fun provideTestDatabase(@ApplicationContext context: Context): WhizDatabase {
        Log.d(TAG, "🔧 Creating test database...")
        return try {
            val database = Room.inMemoryDatabaseBuilder(
                context,
                WhizDatabase::class.java
            ).allowMainThreadQueries() // Allow main thread queries for testing
                .build()
            Log.d(TAG, "✅ Test database created successfully")
            database
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create test database", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideChatDao(database: WhizDatabase): ChatDao {
        Log.d(TAG, "🔧 Creating ChatDao...")
        return try {
            val dao = database.chatDao()
            Log.d(TAG, "✅ ChatDao created successfully")
            dao
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create ChatDao", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: WhizDatabase): MessageDao {
        Log.d(TAG, "🔧 Creating MessageDao...")
        return try {
            val dao = database.messageDao()
            Log.d(TAG, "✅ MessageDao created successfully")
            dao
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create MessageDao", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideWhizRepository(
        apiService: ApiService,
        @ApplicationContext context: Context
    ): WhizRepository {
        Log.d(TAG, "🔧 Creating WhizRepository with apiService=${apiService::class.simpleName}, context=$context")
        return try {
            val repository = WhizRepository(apiService, context)
            Log.d(TAG, "✅ WhizRepository created successfully")
            repository
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create WhizRepository", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authRepositoryProvider: Provider<AuthRepository>,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        Log.d(TAG, "🔧 Creating OkHttpClient...")
        return try {
            val client = OkHttpClient.Builder()
                .authenticator(tokenAuthenticator)
                .addInterceptor(Interceptor { chain ->
                    Log.d(TAG, "🔗 OkHttpClient interceptor called")
                    val originalRequest = chain.request()
                    val requestBuilder = originalRequest.newBuilder()
                    
                    // Skip adding Authorization header for authentication endpoints
                    val isAuthRequest = originalRequest.url.pathSegments.contains("auth")
                    
                    if (!isAuthRequest) {
                        try {
                            // Get AuthRepository instance via provider
                            val authRepository = authRepositoryProvider.get()
                            Log.d(TAG, "🔗 Got AuthRepository from provider")
                            // Get token asynchronously but don't block if it's null
                            val token: String? = runBlocking { 
                                authRepository.serverToken.first() // Get current token, could be null
                            }
                            Log.d(TAG, "🔗 Got server token: ${if (token != null) "present" else "null"}")

                            if (token != null) {
                                requestBuilder.header("Authorization", "Bearer $token")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "🔗 Failed to get auth token in interceptor", e)
                        }
                    }
                    
                    chain.proceed(requestBuilder.build())
                })
                .readTimeout(30, TimeUnit.SECONDS) // Adjust timeouts as needed
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            Log.d(TAG, "✅ OkHttpClient created successfully")
            client
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create OkHttpClient", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideWhizServerRepository(
        okHttpClient: OkHttpClient,
        authRepository: AuthRepository
    ): WhizServerRepository {
        Log.d(TAG, "🔧 Creating WhizServerRepository...")
        return try {
            val repository = WhizServerRepository(okHttpClient, authRepository)
            Log.d(TAG, "✅ WhizServerRepository created successfully")
            repository
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create WhizServerRepository", e)
            throw e
        }
    }
    
    @Provides
    @Singleton
    fun provideAuthRepository(
        @ApplicationContext context: Context,
        authApi: AuthApi
    ): AuthRepository {
        Log.d(TAG, "🔧 Creating AuthRepository with context=$context, authApi=${authApi::class.simpleName}")
        return try {
            val repository = AuthRepository(context, authApi)
            Log.d(TAG, "✅ AuthRepository created successfully")
            repository
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create AuthRepository", e)
            throw e
        }
    }
    
    @Provides
    @Singleton
    fun provideAuthApi(okHttpClient: OkHttpClient): AuthApi {
        Log.d(TAG, "🔧 Creating AuthApi...")
        return try {
            val api = AuthApi(okHttpClient)
            Log.d(TAG, "✅ AuthApi created successfully")
            api
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create AuthApi", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideApiService(): ApiService {
        Log.d(TAG, "🔧 Creating TestApiService...")
        return try {
            val service = TestApiService()
            Log.d(TAG, "✅ TestApiService created successfully")
            service
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create TestApiService", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideSupabaseApi(): SupabaseApi {
        Log.d(TAG, "🔧 Creating TestSupabaseApi...")
        return try {
            val api = TestSupabaseApi()
            Log.d(TAG, "✅ TestSupabaseApi created successfully")
            api
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create TestSupabaseApi", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideSpeechRecognitionService(@ApplicationContext context: Context): SpeechRecognitionService {
        Log.d(TAG, "🔧 Creating SpeechRecognitionService...")
        return try {
            val service = SpeechRecognitionService(context)
            Log.d(TAG, "✅ SpeechRecognitionService created successfully")
            service
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create SpeechRecognitionService", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideTTSManager(@ApplicationContext context: Context): TTSManager {
        Log.d(TAG, "🔧 Creating TTSManager...")
        return try {
            val manager = TTSManager(context)
            Log.d(TAG, "✅ TTSManager created successfully")
            manager
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create TTSManager", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideTokenAuthenticator(
        authRepositoryProvider: Provider<AuthRepository>,
        authApiProvider: Provider<AuthApi>
    ): TokenAuthenticator {
        Log.d(TAG, "🔧 Creating TokenAuthenticator...")
        return try {
            val authenticator = TokenAuthenticator(authRepositoryProvider, authApiProvider)
            Log.d(TAG, "✅ TokenAuthenticator created successfully")
            authenticator
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create TokenAuthenticator", e)
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideUserPreferences(
        apiService: ApiService,
        authRepository: AuthRepository
    ): UserPreferences {
        Log.d(TAG, "🔧 Creating UserPreferences...")
        return try {
            Log.d(TAG, "🔧 UserPreferences: apiService=${apiService::class.simpleName}, authRepository=${authRepository::class.simpleName}")
            val preferences = UserPreferences(apiService, authRepository)
            Log.d(TAG, "✅ UserPreferences created successfully")
            preferences
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create UserPreferences", e)
            throw e
        }
    }

    // Add a provider to verify all ViewModel dependencies are available
    @Provides
    @Singleton
    @Named("DEPENDENCY_STATUS")
    fun provideViewModelDependencyStatus(
        @ApplicationContext context: Context,
        repository: WhizRepository,
        speechRecognitionService: SpeechRecognitionService,
        whizServerRepository: WhizServerRepository,
        authRepository: AuthRepository,
        userPreferences: UserPreferences,
        authApi: AuthApi
    ): String {
        Log.w(TAG, "🔍 DEPENDENCY CHECK: All ViewModel dependencies verified:")
        Log.w(TAG, "  ✅ Context: ${context::class.simpleName}")
        Log.w(TAG, "  ✅ WhizRepository: ${repository::class.simpleName}")
        Log.w(TAG, "  ✅ SpeechRecognitionService: ${speechRecognitionService::class.simpleName}")
        Log.w(TAG, "  ✅ WhizServerRepository: ${whizServerRepository::class.simpleName}")
        Log.w(TAG, "  ✅ AuthRepository: ${authRepository::class.simpleName}")
        Log.w(TAG, "  ✅ UserPreferences: ${userPreferences::class.simpleName}")
        Log.w(TAG, "  ✅ AuthApi: ${authApi::class.simpleName}")
        Log.w(TAG, "🎯 ALL VIEWMODEL DEPENDENCIES AVAILABLE!")
        return "ALL VIEWMODEL DEPENDENCIES AVAILABLE!"
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