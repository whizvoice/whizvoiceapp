package com.example.whiz.di

import android.content.Context
import androidx.room.Room
import com.example.whiz.data.local.ChatDao
import com.example.whiz.data.local.MessageDao
import com.example.whiz.data.local.WhizDatabase
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import javax.inject.Provider

import okhttp3.OkHttpClient
import com.example.whiz.data.remote.WhizServerRepository
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.auth.TokenAuthenticator
import com.example.whiz.data.preferences.UserPreferences
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWhizDatabase(@ApplicationContext context: Context): WhizDatabase {
        return Room.databaseBuilder(
            context,
            WhizDatabase::class.java,
            "whiz_database"
        )
        .fallbackToDestructiveMigration() // 🔧 FIX: Allow destructive migration for production app
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
        @ApplicationContext context: Context,
        messageDao: MessageDao,
        chatDao: ChatDao
    ): WhizRepository {
        return WhizRepository(apiService, context, messageDao, chatDao)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authRepositoryProvider: Provider<AuthRepository>,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .authenticator(tokenAuthenticator)
            .addInterceptor(AuthInterceptor(authRepositoryProvider))
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Optimized auth interceptor that minimizes blocking operations
     */
    private class AuthInterceptor(
        private val authRepositoryProvider: Provider<AuthRepository>
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val originalRequest = chain.request()
            val requestBuilder = originalRequest.newBuilder()
            
            // Skip adding Authorization header for authentication endpoints
            val isAuthRequest = originalRequest.url.pathSegments.contains("auth")
            
            if (!isAuthRequest) {
                try {
                    // Get AuthRepository instance via provider
                    val authRepository = authRepositoryProvider.get()
                    // Get token asynchronously but don't block if it's null
                    val token: String? = runBlocking { 
                        withContext(Dispatchers.IO) {
                            authRepository.serverToken.first() // Get current token, could be null
                        }
                    }

                    if (token != null) {
                        requestBuilder.header("Authorization", "Bearer $token")
                    }
                } catch (e: Exception) {
                    // Log but don't fail the request if we can't get the token
                    android.util.Log.w("AuthInterceptor", "Failed to get auth token", e)
                }
            }
            
            return chain.proceed(requestBuilder.build())
        }
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
    fun provideApiService(okHttpClient: OkHttpClient): ApiService {
        return Retrofit.Builder()
            .baseUrl("https://whizvoice.com/api/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
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