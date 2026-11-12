package com.example.whiz.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY lastMessageTime DESC")
    fun getAllChatsFlow(): Flow<List<ChatEntity>>

    // One-shot read for use inside a transaction (a Flow query would run outside it).
    @Query("SELECT * FROM chats")
    suspend fun getAllChatsSnapshot(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getChatById(id: String): ChatEntity?

    // Returns -1 when a row with this id already exists (it is left untouched).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(chat: ChatEntity): Long

    @Query("SELECT * FROM chats WHERE serverId = :serverId")
    suspend fun getChatByServerId(serverId: Long): ChatEntity?

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Upsert
    suspend fun upsertAll(chats: List<ChatEntity>)

    @Query("UPDATE chats SET serverId = :serverId WHERE id = :id")
    suspend fun setServerId(id: String, serverId: Long): Int

    // Never moves lastMessageTime backwards.
    @Query("UPDATE chats SET lastMessageTime = MAX(lastMessageTime, :timestamp) WHERE id = :id")
    suspend fun updateChatLastMessageTime(id: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE chats SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String): Int

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteChat(id: String): Int

    @Query("DELETE FROM chats WHERE serverId IS NOT NULL AND id NOT IN (:ids)")
    suspend fun deleteSyncedChatsNotIn(ids: List<String>): Int

    // For a full sync that returned zero chats: deleteSyncedChatsNotIn(emptyList()) would expand
    // to NOT IN (), so the empty case gets its own query. Unsynced (serverId IS NULL) rows survive.
    @Query("DELETE FROM chats WHERE serverId IS NOT NULL")
    suspend fun deleteAllSyncedChats(): Int

    @Query("DELETE FROM chats")
    suspend fun deleteAllChats(): Int

    @Query("SELECT * FROM chats WHERE serverId IS NULL AND createdAt < :cutoff")
    suspend fun getUnsyncedChatsOlderThan(cutoff: Long): List<ChatEntity>
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChatFlow(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun getMessageCountForChat(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>): List<Long>

    @Update
    suspend fun updateMessage(message: MessageEntity): Int

    @Update
    suspend fun updateMessages(messages: List<MessageEntity>): Int

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long): Int

    @Query("DELETE FROM messages WHERE chatId = :chatId AND requestId = :requestId AND type = 'ASSISTANT'")
    suspend fun deleteAssistantMessageByRequestId(chatId: String, requestId: String): Int

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages(): Int

    @Query("SELECT * FROM messages ORDER BY id DESC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND requestId = :requestId AND type = 'USER' LIMIT 1")
    suspend fun getUserMessageByRequestId(chatId: String, requestId: String): MessageEntity?

    @Query("UPDATE messages SET chatId = :newChatId WHERE id = :messageId")
    suspend fun updateMessageChatId(messageId: Long, newChatId: String): Int
}

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 4,
    exportSchema = false
)
abstract class WhizDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
}