package com.example.whiz.di

import android.content.Context
import androidx.room.Room
import com.example.whiz.data.local.ChatDao
import com.example.whiz.data.local.MessageDao
import com.example.whiz.data.local.WhizDatabase
import com.example.whiz.data.repository.WhizRepository
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
import com.example.whiz.data.api.SupabaseApi
import com.example.whiz.data.auth.TokenAuthenticator
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
        ).build()
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
        chatDao: ChatDao,
        messageDao: MessageDao
    ): WhizRepository {
        return WhizRepository(chatDao, messageDao)
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
                // Get AuthRepository instance via provider
                val authRepository = authRepositoryProvider.get()
                // Get token synchronously within the interceptor
                val token = runBlocking { authRepository.serverToken.value } // Use runBlocking carefully

                val requestBuilder = originalRequest.newBuilder()
                if (token != null) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                chain.proceed(requestBuilder.build())
            })
            .readTimeout(30, TimeUnit.SECONDS) // Adjust timeouts as needed
            .connectTimeout(30, TimeUnit.SECONDS)
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
    fun provideApiService(okHttpClient: OkHttpClient): ApiService {
        return Retrofit.Builder()
            .baseUrl("https://whizvoice.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideSupabaseApi(okHttpClient: OkHttpClient): SupabaseApi {
        return Retrofit.Builder()
            .baseUrl("https://whizvoice.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SupabaseApi::class.java)
    }
}