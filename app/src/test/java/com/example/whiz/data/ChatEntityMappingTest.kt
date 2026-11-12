package com.example.whiz.data

import com.example.whiz.data.api.ApiService
import com.example.whiz.data.local.toChatEntity
import com.example.whiz.ui.navigation.Screen
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class ChatEntityMappingTest {

    @Test
    fun `toChatEntity maps client_chat_id to id and server id to serverId`() {
        val response = ApiService.ConversationResponse(
            id = 25407L,
            client_chat_id = "7f3a",
            user_id = "u",
            title = "Hello",
            created_at = "2023-12-15T10:30:00Z",
            last_message_time = "2023-12-15T10:35:00Z",
            source = "app"
        )

        val entity = response.toChatEntity()

        assertThat(entity.id).isEqualTo("7f3a")
        assertThat(entity.serverId).isEqualTo(25407L)
    }

    @Test
    fun `newChatRoute returns chat prefix plus a distinct parseable UUID each call`() {
        val first = Screen.Chat.newChatRoute()
        val second = Screen.Chat.newChatRoute()

        assertThat(first).startsWith("chat/")
        assertThat(second).startsWith("chat/")
        UUID.fromString(first.removePrefix("chat/"))
        UUID.fromString(second.removePrefix("chat/"))
        assertThat(first).isNotEqualTo(second)
    }
}
