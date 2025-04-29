package com.example.whiz.data

import com.example.whiz.data.repository.WhizRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreloadManager @Inject constructor(
    private val repository: WhizRepository
) {
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun preloadChatsList() {
        preloadScope.launch {
            repository.getAllChats().collect { }
        }
    }

    fun preloadChat(chatId: Long) {
        preloadScope.launch {
            repository.getMessagesForChat(chatId).collect { }
        }
    }
}