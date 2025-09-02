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
import com.example.whiz.data.ConnectionStateManager
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.auth.TokenAuthenticator
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.di.AppModule
import com.example.whiz.TestCredentialsManager
import com.example.whiz.TestAuthRepository
import com.example.whiz.accessibility.AccessibilityChecker
import com.example.whiz.test_helpers.TestAccessibilityChecker
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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.GsonBuilder
import retrofit2.http.*

/**
 * Test module that provides REAL dependencies for E2E testing with test credentials
 * NO MORE MOCKING - Always use production APIs with whizvoicetest user
 */

/**
 * Interface for capturing ViewModels in tests
 */
interface ViewModelCapture {
    fun onChatViewModelReady(viewModel: com.example.whiz.ui.viewmodels.ChatViewModel)
}

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule {

    private const val TAG = "TestAppModule"

    init {
        Log.w(TAG, "🚀 TestAppModule initialized - REAL E2E testing with test credentials")
    }

    @Provides
    @Singleton
    fun provideTestDatabase(@ApplicationContext context: Context): WhizDatabase {
        Log.d(TAG, "🔧 Creating test database (in-memory for fast tests)...")
        return Room.inMemoryDatabaseBuilder(
            context,
            WhizDatabase::class.java
        ).allowMainThreadQueries()
            .build()
    }

    @Provides
    @Singleton
    fun provideChatDao(database: WhizDatabase): ChatDao = database.chatDao()

    @Provides
    @Singleton
    fun provideMessageDao(database: WhizDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun provideWhizRepository(
        apiService: ApiService,
        @ApplicationContext context: Context,
        messageDao: MessageDao,
        chatDao: ChatDao,
        connectionStateManager: ConnectionStateManager
    ): WhizRepository {
        Log.d(TAG, "🔧 Creating WhizRepository with REAL ApiService and database DAOs")
        return WhizRepository(apiService, context, messageDao, chatDao, connectionStateManager)
    }

    @Provides
    @Singleton
    fun provideTestInterceptor(): TestInterceptor {
        Log.d(TAG, "🔧 Creating TestInterceptor for simulating errors")
        return TestInterceptor()
    }
    
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authRepositoryProvider: Provider<AuthRepository>,
        tokenAuthenticator: TokenAuthenticator,
        testInterceptor: TestInterceptor
    ): OkHttpClient {
        Log.d(TAG, "🔧 Creating OkHttpClient with TestInterceptor for controlled testing...")
        return OkHttpClient.Builder()
            .authenticator(tokenAuthenticator)
            .addInterceptor(testInterceptor) // Add test interceptor first
            .addInterceptor(Interceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                
                val isAuthRequest = originalRequest.url.pathSegments.contains("auth")
                
                if (!isAuthRequest) {
                    try {
                        val authRepository = authRepositoryProvider.get()
                        val token: String? = runBlocking { 
                            authRepository.serverToken.first()
                        }
                        if (token != null) {
                            requestBuilder.header("Authorization", "Bearer $token")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get auth token in interceptor", e)
                    }
                }
                
                chain.proceed(requestBuilder.build())
            })
            .readTimeout(5, TimeUnit.SECONDS) // Shorter timeout for tests
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideConnectionStateManager(): ConnectionStateManager {
        Log.d(TAG, "🔧 Creating ConnectionStateManager for tests...")
        return ConnectionStateManager()
    }

    @Provides
    @Singleton
    fun provideWhizServerRepository(
        okHttpClient: OkHttpClient,
        authRepository: AuthRepository,
        connectionStateManager: ConnectionStateManager
    ): WhizServerRepository {
        Log.d(TAG, "🔧 Creating REAL WhizServerRepository...")
        return WhizServerRepository(okHttpClient, authRepository, connectionStateManager)
    }
    
    @Provides
    @Singleton
    fun provideAuthRepository(
        @ApplicationContext context: Context,
        authApi: AuthApi
    ): AuthRepository {
        Log.d(TAG, "🔧 Creating TEST AuthRepository that bypasses Google OAuth...")
        // Return TestAuthRepository but cast to AuthRepository interface
        // This allows tests to bypass Google OAuth while still hitting the real server
        return TestAuthRepository(context, authApi)
    }
    
    @Provides
    @Singleton
    fun provideAuthApi(okHttpClient: OkHttpClient): AuthApi {
        Log.d(TAG, "🔧 Creating REAL AuthApi...")
        return AuthApi(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideApiService(okHttpClient: OkHttpClient): ApiService {
        Log.d(TAG, "🔧 Creating REAL ApiService for production API...")
        val credentials = TestCredentialsManager.credentials
        val baseUrl = credentials.testEnvironment.apiBaseUrl
        Log.d(TAG, "Using API base URL: $baseUrl")
        
        // Ensure baseUrl ends with / as required by Retrofit
        val retrofitBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        val gson = GsonBuilder()
            .serializeNulls() // Include null values in JSON
            .create()
        
        val retrofit = Retrofit.Builder()
            .baseUrl(retrofitBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        return retrofit.create(ApiService::class.java)
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
        Log.d(TAG, "🔧 Creating UserPreferences with REAL services...")
        return UserPreferences(apiService, authRepository)
    }
    
    @Provides
    @Singleton
    fun provideAccessibilityChecker(
        @ApplicationContext context: Context
    ): AccessibilityChecker {
        Log.d(TAG, "🔧 Creating TestAccessibilityChecker for testing...")
        return TestAccessibilityChecker(context)
    }
} 