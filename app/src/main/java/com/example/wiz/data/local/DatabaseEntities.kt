package com.example.wiz.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastMessageTime: Long = System.currentTimeMillis()
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
    ]
)
@TypeConverters(MessageTypeConverter::class)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: Long,
    val content: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis()
)

class MessageTypeConverter {
    @TypeConverter
    fun fromMessageType(messageType: MessageType): String {
        return messageType.name
    }

    @TypeConverter
    fun toMessageType(value: String): MessageType {
        return MessageType.valueOf(value)
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