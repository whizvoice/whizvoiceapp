package com.example.wiz.data.repository

import android.util.Log
import com.example.wiz.data.local.ChatDao
import com.example.wiz.data.local.ChatEntity
import com.example.wiz.data.local.MessageDao
import com.example.wiz.data.local.MessageEntity
import com.example.wiz.data.local.MessageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WizRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao
) {
    private val TAG = "WizRepository"

    // Chat operations
    fun getAllChats(): Flow<List<ChatEntity>> {
        return chatDao.getAllChatsFlow()
            .onEach { Log.d(TAG, "getAllChats: retrieved ${it.size} chats") }
            .catch { e ->
                Log.e(TAG, "Error getting chats", e)
                emit(emptyList())
            }
    }

    suspend fun getChatById(chatId: Long): ChatEntity? {
        return try {
            val chat = chatDao.getChatById(chatId)
            Log.d(TAG, "getChatById: retrieved chat with id $chatId: ${chat?.title}")
            chat
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat with id $chatId", e)
            null
        }
    }

    suspend fun createChat(title: String): Long {
        return try {
            val chat = ChatEntity(title = title)
            val id = chatDao.insertChat(chat)
            Log.d(TAG, "createChat: created chat with id $id and title $title")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Error creating chat with title $title", e)
            -1
        }
    }

    suspend fun updateChatTitle(chatId: Long, title: String) {
        try {
            val chat = chatDao.getChatById(chatId) ?: return
            chatDao.updateChat(chat.copy(title = title))
            Log.d(TAG, "updateChatTitle: updated chat $chatId title to $title")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chat $chatId title", e)
        }
    }

    suspend fun updateChatLastMessageTime(chatId: Long) {
        try {
            chatDao.updateChatLastMessageTime(chatId)
            Log.d(TAG, "updateChatLastMessageTime: updated chat $chatId last message time")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chat $chatId last message time", e)
        }
    }

    suspend fun deleteAllChats() {
        try {
            chatDao.deleteAllChats()
            messageDao.deleteAllMessages()
            Log.d(TAG, "deleteAllChats: deleted all chats and messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all chats", e)
        }
    }

    // Message operations
// In WizRepository class
    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>> {
        // Add debug logging
        Log.d("WizRepository", "Getting messages for chat ID: $chatId")

        return messageDao.getMessagesForChatFlow(chatId)
            .onEach { messages ->
                Log.d("WizRepository", "Retrieved ${messages.size} messages for chat $chatId")
            }
            .catch { e ->
                Log.e("WizRepository", "Error getting messages for chat $chatId", e)
                emit(emptyList())
            }
    }

    suspend fun getMessageCountForChat(chatId: Long): Int {
        return try {
            val count = messageDao.getMessageCountForChat(chatId)
            Log.d(TAG, "getMessageCountForChat: chat $chatId has $count messages")
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error getting message count for chat $chatId", e)
            0
        }
    }

    suspend fun addUserMessage(chatId: Long, content: String): Long {
        return try {
            val message = MessageEntity(
                chatId = chatId,
                content = content,
                type = MessageType.USER
            )
            val messageId = messageDao.insertMessage(message)
            chatDao.updateChatLastMessageTime(chatId)
            Log.d(TAG, "addUserMessage: added user message $messageId to chat $chatId")
            messageId
        } catch (e: Exception) {
            Log.e(TAG, "Error adding user message to chat $chatId", e)
            -1
        }
    }

    suspend fun addAssistantMessage(chatId: Long, content: String): Long {
        return try {
            val message = MessageEntity(
                chatId = chatId,
                content = content,
                type = MessageType.ASSISTANT
            )
            val messageId = messageDao.insertMessage(message)
            chatDao.updateChatLastMessageTime(chatId)
            Log.d(TAG, "addAssistantMessage: added assistant message $messageId to chat $chatId")
            messageId
        } catch (e: Exception) {
            Log.e(TAG, "Error adding assistant message to chat $chatId", e)
            -1
        }
    }

    // Auto-save logic
    suspend fun shouldPersistChat(chatId: Long): Boolean {
        val count = getMessageCountForChat(chatId)
        val shouldPersist = count >= 3
        Log.d(TAG, "shouldPersistChat: chat $chatId has $count messages, should persist: $shouldPersist")
        return shouldPersist
    }

    // Helper to get chat title based on first message or default title
    fun deriveChatTitle(userMessage: String): String {
        // Extract first line or first few words for the title
        val firstLine = userMessage.trim().split("\n").first()
        return if (firstLine.length > 20) {
            "${firstLine.take(20)}..."
        } else {
            firstLine
        }
    }
}