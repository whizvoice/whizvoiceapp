package com.example.whiz.viewmodels

import com.example.whiz.ui.viewmodels.AUTH_DIALOG_ERROR_CODES
import com.example.whiz.ui.viewmodels.PLAIN_MESSAGE_ERROR_CODES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server error codes that should open the API-key dialog (which routes the user to
 * Settings), versus those shown as a plain chat message (no dialog, no "Server
 * Error:" prefix).
 */
class ServerErrorCodeRoutingTest {

    @Test
    fun usageLimit_isPlainChatMessage_notDialog() {
        assertTrue("CLAUDE_USAGE_LIMIT" in PLAIN_MESSAGE_ERROR_CODES)
        assertFalse("CLAUDE_USAGE_LIMIT" in AUTH_DIALOG_ERROR_CODES)
    }

    @Test
    fun overloaded_isPlainChatMessage() {
        assertTrue("CLAUDE_OVERLOADED" in PLAIN_MESSAGE_ERROR_CODES)
    }

    @Test
    fun existingAuthCodes_stillOpenApiKeyDialog() {
        assertTrue("CLAUDE_AUTHENTICATION_ERROR" in AUTH_DIALOG_ERROR_CODES)
        assertTrue("CLAUDE_API_KEY_MISSING" in AUTH_DIALOG_ERROR_CODES)
    }

    @Test
    fun genericApiError_staysInline() {
        assertFalse("CLAUDE_API_ERROR" in AUTH_DIALOG_ERROR_CODES)
        assertFalse("CLAUDE_OVERLOADED" in AUTH_DIALOG_ERROR_CODES)
    }
}
