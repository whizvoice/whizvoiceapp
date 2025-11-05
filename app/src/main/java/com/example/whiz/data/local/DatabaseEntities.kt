package com.example.whiz.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import com.example.whiz.data.api.ApiService

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageTime: Long = System.currentTimeMillis(),
    val optimisticChatId: Long? = null  // Stores the original optimistic ID when chat was created offline
)

enum class MessageType {
    USER,
    ASSISTANT
}

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
@TypeConverters(MessageTypeConverter::class)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: Long,
    val content: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val requestId: String? = null // 🔧 NEW: Link assistant messages to their user message request
)

class MessageTypeConverter {
    @TypeConverter
    fun fromMessageType(messageType: MessageType): String {
        return messageType.name
    }

    @TypeConverter
    fun toMessageType(value: String): MessageType {
        return parseMessageType(value)
    }
}

// Helper to format dates for UI display
object DateFormatter {
    private val todayFormatter = DateTimeFormatter.ofPattern("h:mm a")
    private val pastFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun formatMessageTime(timestamp: Long): String {
        val messageTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        )
        val today = LocalDateTime.now().toLocalDate()

        return if (messageTime.toLocalDate() == today) {
            messageTime.format(todayFormatter)
        } else {
            messageTime.format(pastFormatter)
        }
    }
}

// Extension functions to convert between API models and local entities

// Convert API ConversationResponse to local ChatEntity
fun ApiService.ConversationResponse.toChatEntity(): ChatEntity {
    return ChatEntity(
        id = this.id,
        title = sanitizeChatTitle(this.title),
        createdAt = parseTimestampToMillis(this.created_at),
        lastMessageTime = parseTimestampToMillis(this.last_message_time),
        optimisticChatId = this.optimistic_chat_id?.toLongOrNull()
    )
}

// Helper function to sanitize chat title
private fun sanitizeChatTitle(title: String): String {
    return title.trim()
        .replace(Regex("[\\n\\t\\r]"), " ")
        .replace(Regex("\\s+"), " ")
        .take(200)
        .ifBlank { "Untitled Chat" }
}

// Convert local ChatEntity to API ConversationCreate
fun ChatEntity.toConversationCreate(): ApiService.ConversationCreate {
    return ApiService.ConversationCreate(
        title = this.title,
        source = "app"
    )
}

// Convert API MessageResponse to local MessageEntity  
fun ApiService.MessageResponse.toMessageEntity(): MessageEntity {
    // Log the raw server response for debugging
    android.util.Log.d("DatabaseEntities", "Converting server message to entity:")
    android.util.Log.d("DatabaseEntities", "  Server timestamp: '${this.timestamp}'")
    android.util.Log.d("DatabaseEntities", "  Request ID: '${this.request_id}'")
    android.util.Log.d("DatabaseEntities", "  Content preview: '${this.content.take(50)}'")
    android.util.Log.d("DatabaseEntities", "  Message type: '${this.message_type}'")

    val parsedTimestamp = parseTimestampToMillis(this.timestamp)
    android.util.Log.d("DatabaseEntities", "  Parsed timestamp: $parsedTimestamp (${java.time.Instant.ofEpochMilli(parsedTimestamp)})")

    return MessageEntity(
        id = 0,  // Let Room auto-generate the ID - don't use server ID!
        chatId = this.conversation_id,
        content = this.content,
        type = parseMessageType(this.message_type),  // Parse message_type from API response
        timestamp = parsedTimestamp,
        requestId = this.request_id  // 🔧 FIXED: Preserve request_id from server
    )
}

// Helper function to safely parse message type with fallback
private fun parseMessageType(messageType: String): MessageType {
    return try {
        MessageType.valueOf(messageType.uppercase())
    } catch (e: IllegalArgumentException) {
        // Default to USER for unknown message types
        MessageType.USER
    }
}

// Convert local MessageEntity to API MessageCreate
fun MessageEntity.toMessageCreate(): ApiService.MessageCreate {
    return ApiService.MessageCreate(
        conversation_id = this.chatId,
        content = this.content,
        message_type = this.type.name
    )
}

// Helper function to parse ISO timestamp to millis
private fun parseTimestampToMillis(timestamp: String): Long {
    // First, try to parse as epoch millis if it's a number
    if (timestamp.all { it.isDigit() }) {
        try {
            val millis = timestamp.toLong()
            android.util.Log.d("DatabaseEntities", "Parsed timestamp '$timestamp' as epoch millis: $millis")
            return millis
        } catch (e: NumberFormatException) {
            android.util.Log.e("DatabaseEntities", "Failed to parse numeric timestamp '$timestamp': ${e.message}")
        }
    }
    
    // Try to parse as ISO format
    return try {
        // Handle both Z and +00:00 timezone formats
        // Instant.parse handles: "2023-12-15T10:30:00Z", "2023-12-15T10:30:00.123Z"
        // For +00:00 format, we need to normalize it
        val normalizedTimestamp = if (timestamp.contains("+00:00")) {
            android.util.Log.d("DatabaseEntities", "Normalizing timestamp with +00:00 timezone: '$timestamp' -> '${timestamp.replace("+00:00", "Z")}'")
            timestamp.replace("+00:00", "Z")
        } else {
            android.util.Log.d("DatabaseEntities", "Timestamp already in compatible format: '$timestamp'")
            timestamp
        }
        
        val instant = Instant.parse(normalizedTimestamp)
        val millis = instant.toEpochMilli()
        android.util.Log.d("DatabaseEntities", "Successfully parsed timestamp '$timestamp' (normalized: '$normalizedTimestamp') to $millis (${Instant.ofEpochMilli(millis)})")
        millis
    } catch (e: DateTimeParseException) {
        android.util.Log.e("DatabaseEntities", "Failed to parse timestamp '$timestamp' as ISO format: ${e.message}")
        
        // Last resort: use current time but log it as a critical error
        val currentTime = System.currentTimeMillis()
        android.util.Log.e("DatabaseEntities", "CRITICAL: Failed to parse timestamp '$timestamp' - using current time $currentTime as fallback!")
        currentTime
    }
}

// Subscription Status data class (not a Room entity, just a data model)
data class SubscriptionStatus(
    val has_subscription: Boolean,
    val subscription_id: String? = null,
    val status: String? = null,
    val current_period_end: Long? = null,
    val cancel_at_period_end: Boolean? = null
)