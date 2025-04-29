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

import okhttp3.OkHttpClient
import com.example.whiz.data.remote.WhizServerRepository
import java.util.concurrent.TimeUnit

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
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS) // Adjust timeouts as needed
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideWhizServerRepository(okHttpClient: OkHttpClient): WhizServerRepository {
        return WhizServerRepository(okHttpClient)
    }
}