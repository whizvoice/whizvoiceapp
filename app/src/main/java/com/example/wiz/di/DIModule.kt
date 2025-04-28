package com.example.wiz.di

import android.content.Context
import androidx.room.Room
import com.example.wiz.data.local.ChatDao
import com.example.wiz.data.local.MessageDao
import com.example.wiz.data.local.WizDatabase
import com.example.wiz.data.repository.WizRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

import okhttp3.OkHttpClient
import com.example.wiz.data.remote.WizServerRepository
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWizDatabase(@ApplicationContext context: Context): WizDatabase {
        return Room.databaseBuilder(
            context,
            WizDatabase::class.java,
            "wiz_database"
        ).build()
    }

    @Provides
    @Singleton
    fun provideChatDao(database: WizDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    @Singleton
    fun provideMessageDao(database: WizDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    @Singleton
    fun provideWizRepository(
        chatDao: ChatDao,
        messageDao: MessageDao
    ): WizRepository {
        return WizRepository(chatDao, messageDao)
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
    fun provideWizServerRepository(okHttpClient: OkHttpClient): WizServerRepository {
        return WizServerRepository(okHttpClient)
    }
}