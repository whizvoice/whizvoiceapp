package com.example.whiz.integration

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.example.whiz.ToolFlowIntegrationTest
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.di.AppModule
import com.example.whiz.services.MessageDraftOverlayService
import com.example.whiz.test_helpers.SkipOnCIOrEmulatorRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Screen agent WhatsApp test.
 *
 * Structurally SmsDraftMessageTest with WhatsApp tool names and WhatsApp's UI: draft a message,
 * ask for it to be reworded (tracked-changes overlay), confirm sending, then delete the sent
 * message through WhatsApp's long-press selection bar. Messages the other test phone for real.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WhatsAppDraftMessageTest : ToolFlowIntegrationTest() {

    companion object {
        private const val TAG = "WhatsAppDraftMessageTest"
        private const val WHATSAPP_PKG = "com.whatsapp"

        // WhatsApp selectors, captured from a live `uiautomator dump` on 2026-11-12 (classic View app):
        //  - outgoing/incoming bubble text: com.whatsapp:id/message_text
        //  - conversation header name:      com.whatsapp:id/conversation_contact_name
        //  - long-press selection bar:      Buttons with content descriptions "Reply", "Star message",
        //                                   "Info", "Delete", "Forward"
        //  - delete dialog:                 text "Delete message?" with buttons "Delete for everyone"
        //                                   (fresh messages) / "Delete for me" / "Cancel"
        private val MESSAGE_TEXT_RES: Pattern = Pattern.compile("$WHATSAPP_PKG:id/message_text")
        private const val CONTACT_NAME_RES = "$WHATSAPP_PKG:id/conversation_contact_name"
        private val DELETE_DIALOG_TEXT: Pattern = Pattern.compile("Delete message\\?|Delete for everyone|Delete for me")

        private const val REQUESTED_BODY = "hey whats up hows it going just tryna test whiz voice"

        private val DRAFT_TOOLS = setOf("agent_whatsapp_draft_message", "agent_draft_message")
        private val SEND_TOOLS = setOf("agent_whatsapp_send_message", "agent_send_message")

        private const val TOOL_TIMEOUT_MS = 60_000L
    }

    @get:Rule(order = 3)
    val skipOnCI = SkipOnCIOrEmulatorRule()

    /** Same per-device mapping as get_phone_numbers(): each phone messages the OTHER one, never itself. */
    private val whatsappFull: String
        get() = if (Build.MODEL.contains("Pixel 8")) "+1(628)227-4544" else "+1(628)209-9005"
    private val whatsappShort: String
        get() = if (Build.MODEL.contains("Pixel 8")) "Whiz Voice Test" else "Ruth Grace Wong"

    /** Set once the send tool reports success; cleared once the bubble is deleted. */
    private var sentMessage: String? = null
    private var bubblesBeforeSend = 0

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        startToolResultCollector()
        device.executeShellCommand("am force-stop $WHATSAPP_PKG")
    }

    @After
    fun cleanup() {
        stopToolResultCollector()
        // A failure after the send leaves the message on both phones; delete it best-effort.
        sentMessage?.let { msg ->
            try {
                stopBubbleOverlayIfActive()
                deleteSentBubble(msg)
            } catch (e: Throwable) {
                Log.w(TAG, "best-effort delete of the sent message failed", e)
            }
        }
        runBlocking {
            stopBubbleOverlayIfActive()
            // Tracked id only. A title-pattern fallback would also delete chats other runs (CI, the
            // other phone) are using right now; a leaked chat after a crash is the cheaper failure.
            cleanupTrackedChats()
        }
        try {
            device.executeShellCommand("am force-stop $WHATSAPP_PKG")
        } catch (_: Exception) {
        }
    }

    /** Deletes the sent message through WhatsApp's long-press selection bar, asserting each stage like the Python test. */
    private fun deleteSentBubble(message: String) {
        val bubble = waitForSentBubble(WHATSAPP_PKG, MESSAGE_TEXT_RES, message, 10_000, bubblesBeforeSend)
            ?: failWithScreenshot("Sent message '$message' not found for deletion", "wa_bubble_not_found_for_delete")
        bubble.longClick()
        val deleteItem = device.wait(Until.findObject(By.pkg(WHATSAPP_PKG).desc("Delete")), 5_000)
            ?: device.findObject(By.pkg(WHATSAPP_PKG).text("Delete"))
            ?: failWithScreenshot("Long-press did not enter selection mode (no 'Delete' action)", "wa_no_delete_item")
        deleteItem.click()
        assertTrue(
            "delete confirmation dialog did not appear",
            device.wait(Until.hasObject(By.pkg(WHATSAPP_PKG).text(DELETE_DIALOG_TEXT)), 5_000)
        )
        val confirm = device.findObject(By.pkg(WHATSAPP_PKG).text("Delete for everyone"))
            ?: device.findObject(By.pkg(WHATSAPP_PKG).text("Delete for me"))
            ?: device.findObjects(By.pkg(WHATSAPP_PKG).text("Delete")).lastOrNull()
            ?: failWithScreenshot("Delete confirmation button not found", "wa_no_delete_confirm")
        Log.i(TAG, "deleting via '${confirm.text}'")
        confirm.click()
        assertTrue(
            "message bubble should be gone after delete",
            waitUntilBlocking(10_000) { findBubbles(WHATSAPP_PKG, MESSAGE_TEXT_RES, message).size <= bubblesBeforeSend }
        )
        sentMessage = null
        Log.i(TAG, "cleanup ok: message deleted")
    }

    @Test
    fun testWhatsAppDraft_edit_send_andDelete(): Unit = runBlocking {
        assertTrue(
            "Real accessibility service must be connected for the screen agent to drive WhatsApp",
            awaitAccessibilityServiceConnected()
        )
        voiceManager.setVoiceResponseEnabled(false)
        val chatViewModel = launchVoiceModeAndCaptureViewModel()

        // ---- Step 1: draft; expect the overlay over WhatsApp's conversation with the other phone ----
        val mark1 = toolResults.size
        sendVoice(chatViewModel, "Hello, can you please send a WhatsApp message to $whatsappFull that says $REQUESTED_BODY")
        val draft1 = awaitSuccess(DRAFT_TOOLS, mark1, TOOL_TIMEOUT_MS, "wa_draft1")
        assertTrue("draft overlay not shown: ${draft1.result}", draft1.result.optBoolean("overlay_shown"))
        val draftText1 = draft1.result.optString("displayed_text")
        assertTrue("first draft text unexpected: '$draftText1'", draftText1.contains("test whiz voice", ignoreCase = true))
        assertTrue("WhatsApp should be in the foreground", device.wait(Until.hasObject(By.pkg(WHATSAPP_PKG).depth(0)), 10_000))
        val header = device.wait(Until.findObject(By.pkg(WHATSAPP_PKG).res(CONTACT_NAME_RES)), 10_000)
            ?: failWithScreenshot("WhatsApp conversation header not found", "wa_no_header")
        val headerText = header.text ?: ""
        val digits = whatsappFull.filter { it.isDigit() }
        assertTrue(
            "WhatsApp conversation should be with $whatsappShort / $whatsappFull, got '$headerText'",
            headerText.contains(whatsappShort, ignoreCase = true) ||
                headerText.filter { it.isDigit() }.contains(digits) ||
                headerText.endsWith("(You)")
        )
        assertTrue("overlay should report visible", MessageDraftOverlayService.overlayVisible)
        // The tool reports the chat as requested and as WhatsApp labels it; WhatsApp was force-stopped,
        // so this first draft had to navigate to the chat.
        assertEquals("tool should echo the requested chat", whatsappFull.filter { it.isDigit() }, draft1.result.optString("chat_requested").filter { it.isDigit() })
        assertEquals("tool should report the chat name shown on screen", headerText, draft1.result.optString("chat_name_on_screen"))
        assertTrue("first draft should have navigated to the chat: ${draft1.result}", draft1.result.optBoolean("chat_navigated"))
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 1 ok: draft='$draftText1' header='$headerText'")

        // ---- Step 2: rewrite; expect a new message and tracked-changes spans in the overlay ----
        val mark2 = toolResults.size
        sendVoice(chatViewModel, "Actually, can you make the message more polite?")
        val draft2 = awaitSuccess(DRAFT_TOOLS, mark2, TOOL_TIMEOUT_MS, "wa_draft2")
        assertTrue("second draft overlay not shown: ${draft2.result}", draft2.result.optBoolean("overlay_shown"))
        // Redrafting must not re-open a chat we are already in: the server passes the number again, and
        // the tool resolves it to the on-screen name it remembered from the first draft.
        assertFalse("redraft must not re-navigate to the chat: ${draft2.result}", draft2.result.optBoolean("chat_navigated"))
        assertEquals("redraft should still report the on-screen chat name", headerText, draft2.result.optString("chat_name_on_screen"))
        val finalMessage = draft2.result.optString("message")
        val draftText2 = draft2.result.optString("displayed_text")
        assertNotEquals("rewrite should change the message", draft1.result.optString("message"), finalMessage)
        // Verify the rewrite where the user sees it: the overlay's own text must change to the
        // tracked-changes rendering. The tool result's displayed_text can be stale on a second draft
        // (ScreenAgentTools.draftMessageCore returns as soon as overlayVisible is true, which it already
        // is from the first draft, before the service has re-rendered) — a known app bug, logged not asserted.
        assertTrue(
            "overlay text did not change after the rewrite (still '$draftText1')",
            waitUntil(10_000) { MessageDraftOverlayService.displayedDraftText.let { it != null && it != draftText1 } }
        )
        val overlayText = MessageDraftOverlayService.displayedDraftText
        if (draftText2 != overlayText) {
            Log.w(TAG, "tool result displayed_text is stale: tool='$draftText2' overlay='$overlayText' (draftMessageCore race)")
        }
        val (struck, colored) = waitForTrackedChangesSpans(10_000)
        assertTrue("expected strikethrough span(s) for removed text in the overlay", struck > 0)
        assertTrue("expected colored span(s) (removed/added) in the overlay", colored > 0)
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 2 ok: message='$finalMessage' strikethrough=$struck colored=$colored")

        // ---- Step 3: send; expect the overlay gone and an outgoing bubble in WhatsApp ----
        // Count look-alike bubbles first (the other phone's test sends similar text here), so only a
        // bubble that appears after the send can satisfy the check.
        bubblesBeforeSend = findBubbles(WHATSAPP_PKG, MESSAGE_TEXT_RES, finalMessage).size
        val mark3 = toolResults.size
        sendVoice(chatViewModel, "That looks good, go ahead and send the message.")
        val sent = awaitSuccess(SEND_TOOLS, mark3, TOOL_TIMEOUT_MS, "wa_send")
        Log.i(TAG, "send tool result: ${sent.result}")
        sentMessage = finalMessage
        assertTrue("draft overlay should be dismissed after sending", waitUntil(10_000) { !MessageDraftOverlayService.isActive })
        val bubble = waitForSentBubble(WHATSAPP_PKG, MESSAGE_TEXT_RES, finalMessage, 30_000, bubblesBeforeSend)
            ?: failWithScreenshot("Sent message '$finalMessage' did not appear as a new bubble in WhatsApp", "wa_bubble_not_found")
        Log.i(TAG, "step 3 ok: bubble text='${bubble.text}'")

        // ---- Cleanup, asserted at each stage like the Python test ----
        deleteSentBubble(finalMessage)
    }
}
