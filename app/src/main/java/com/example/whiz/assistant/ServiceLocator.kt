package com.example.whiz.assistant

import android.content.Context
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.data.remote.WhizServerRepository
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.data.local.ChatDao
import com.example.whiz.data.local.MessageDao
import com.example.whiz.data.local.WhizDatabase
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.data.api.ApiService
import androidx.room.Room
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ServiceLocator {
    private var chatViewModel: ChatViewModel? = null
    private var speechRecognitionService: SpeechRecognitionService? = null
    private var ttsManager: TTSManager? = null
    private var whizRepository: WhizRepository? = null
    private var whizServerRepository: WhizServerRepository? = null
    private var authRepository: AuthRepository? = null
    private var userPreferences: UserPreferences? = null
    private var database: WhizDatabase? = null
    private var okHttpClient: OkHttpClient? = null
    private var authApi: AuthApi? = null
    private var apiService: ApiService? = null

    fun getChatViewModel(context: Context): ChatViewModel {
        if (chatViewModel == null) {
            val speechService = getSpeechRecognitionService(context)
            val repo = getWhizRepository(context)
            val serverRepo = getWhizServerRepository(context)
            val authRepo = getAuthRepository(context)
            val userPrefs = getUserPreferences(context)
            val ttsManager = getTTSManager(context)
            chatViewModel = ChatViewModel(context.applicationContext, repo, speechService, serverRepo, authRepo, userPrefs, ttsManager)
        }
        return chatViewModel!!
    }

    private fun getSpeechRecognitionService(context: Context): SpeechRecognitionService {
        if (speechRecognitionService == null) {
            speechRecognitionService = SpeechRecognitionService(context.applicationContext)
        }
        return speechRecognitionService!!
    }

    private fun getTTSManager(context: Context): TTSManager {
        if (ttsManager == null) {
            ttsManager = TTSManager(context.applicationContext)
        }
        return ttsManager!!
    }

    private fun getWhizRepository(context: Context): WhizRepository {
        if (whizRepository == null) {
            val api = getApiService()
            whizRepository = WhizRepository(api, context)
        }
        return whizRepository!!
    }

    private fun getWhizServerRepository(context: Context): WhizServerRepository {
        if (whizServerRepository == null) {
            val client = getOkHttpClient()
            val authRepo = getAuthRepository(context)
            whizServerRepository = WhizServerRepository(client, authRepo)
        }
        return whizServerRepository!!
    }

    private fun getAuthRepository(context: Context): AuthRepository {
        if (authRepository == null) {
            val api = getAuthApi()
            authRepository = AuthRepository(context.applicationContext, api)
        }
        return authRepository!!
    }

    private fun getDatabase(context: Context): WhizDatabase {
        if (database == null) {
            database = Room.databaseBuilder(
                context.applicationContext,
                WhizDatabase::class.java,
                "whiz_database"
            ).build()
        }
        return database!!
    }

    private fun getOkHttpClient(): OkHttpClient {
        if (okHttpClient == null) {
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        return okHttpClient!!
    }

    private fun getAuthApi(): AuthApi {
        if (authApi == null) {
            authApi = AuthApi(getOkHttpClient())
        }
        return authApi!!
    }

    private fun getApiService(): ApiService {
        if (apiService == null) {
            apiService = Retrofit.Builder()
                .baseUrl("https://whizvoice.com/")
                .client(getOkHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
        return apiService!!
    }

    private fun getUserPreferences(context: Context): UserPreferences {
        if (userPreferences == null) {
            val api = getApiService()
            val authRepo = getAuthRepository(context)
            userPreferences = UserPreferences(api, authRepo)
        }
        return userPreferences!!
    }
} 