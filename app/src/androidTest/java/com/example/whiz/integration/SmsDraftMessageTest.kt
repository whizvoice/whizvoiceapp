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
 * Instrumented version of the screen-agent SMS test (run_screen_agent_tests.py::test_sms_draft_message).
 *
 * Drives the same flow — draft an SMS, ask for it to be reworded, confirm sending — but asserts on
 * in-process signals instead of screenshots judged by a vision model:
 *
 *  - [com.example.whiz.tools.ToolExecutor.toolResults] tells us exactly which tools ran and what they
 *    reported (overlay_shown, displayed_text), so the three overlay checks become exact assertions.
 *  - The tracked-changes rendering (red strikethrough for removed text, blue for added) is checked
 *    by reading the overlay TextView's spans through the accessibility tree. Styling spans survive
 *    into AccessibilityNodeInfo text; it is only UiObject2.getText()/`uiautomator dump` that flatten
 *    them to strings.
 *  - Whether Google Messages really sent it is checked with UiDevice on the Messages window: the
 *    final draft text must appear as a message bubble (a `message_text` node that is not the compose
 *    field).
 *
 * Needs the real WhizAccessibilityService bound (BaseIntegrationTest.setUp ensures this), a logged-in
 * test account, network access to the Whiz backend, and the other test phone reachable by SMS.
 * Messages a real number, exactly like the screen-agent test, and deletes the message afterwards.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmsDraftMessageTest : ToolFlowIntegrationTest() {

    companion object {
        private const val TAG = "SmsDraftMessageTest"
        private const val MESSAGES_PKG = "com.google.android.apps.messaging"
        // Messages' conversation screen is Compose; its bubbles expose the test tag "message_text"
        // as a bare resource id (no package prefix). Accept either form.
        private val MESSAGE_TEXT_RES: Pattern = Pattern.compile("(.*:id/)?message_text")
        // Delete-confirmation dialog: old title "Delete this message?" or the Oct 2026 radio options.
        private val DELETE_DIALOG_TEXT: Pattern = Pattern.compile("Delete this message.*|Delete for everyone|Delete for me")

        // What the test asks for; the agent may reword it in step 2.
        private const val REQUESTED_BODY = "hey testing SMS from whiz voice"

        // The SMS flow uses the sms-specific tools; the generic ones are accepted too.
        private val DRAFT_TOOLS = setOf("agent_sms_draft_message", "agent_draft_message")
        private val SEND_TOOLS = setOf("agent_sms_send_message", "agent_send_message")

        private const val DRAFT_TIMEOUT_MS = 60_000L
        private const val SEND_TIMEOUT_MS = 60_000L
    }

    @get:Rule(order = 3)
    val skipOnCI = SkipOnCIOrEmulatorRule()

    /** Same per-device mapping as run_screen_agent_tests.py: each phone messages the OTHER one. */
    private val smsRecipient: String
        get() = if (Build.MODEL.contains("Pixel 8")) "Whiz Voice Test" else "Ruth Grace Wong"

    /** Set once the send tool reports success; cleared once the bubble is deleted. */
    private var sentMessage: String? = null
    private var bubblesBeforeSend = 0

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        startToolResultCollector()
        // Start Messages from a known state, as the screen-agent test does.
        device.executeShellCommand("am force-stop $MESSAGES_PKG")
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
            device.executeShellCommand("am force-stop $MESSAGES_PKG")
        } catch (_: Exception) {
        }
    }

    /**
     * Deletes the sent message through Messages' long-press menu, asserting each stage like the
     * Python test. Older Messages: a dialog titled "Delete this message?". Messages Oct 2026 (RCS
     * chats): a dialog titled "Delete" with "Delete for everyone" / "Delete for me" radio options;
     * prefer "for everyone" so the other phone is cleaned up too. Both end with a "Delete" button.
     */
    private fun deleteSentBubble(message: String) {
        val bubble = waitForSentBubble(MESSAGES_PKG, MESSAGE_TEXT_RES, message, 10_000, bubblesBeforeSend)
            ?: failWithScreenshot("Sent message '$message' not found for deletion", "sms_bubble_not_found_for_delete")
        bubble.longClick()
        val deleteItem = device.wait(Until.findObject(By.pkg(MESSAGES_PKG).text("Delete")), 5_000)
            ?: failWithScreenshot("Long-press menu with 'Delete' did not appear", "sms_no_delete_menu")
        deleteItem.click()
        val confirmShown = device.wait(Until.hasObject(By.pkg(MESSAGES_PKG).text(DELETE_DIALOG_TEXT)), 5_000)
        assertTrue("delete confirmation dialog did not appear", confirmShown)
        device.findObject(By.pkg(MESSAGES_PKG).text("Delete for everyone"))?.click()
        val confirmButton = device.findObjects(By.pkg(MESSAGES_PKG).text("Delete")).lastOrNull()
            ?: failWithScreenshot("Delete confirmation button not found", "sms_no_delete_confirm")
        confirmButton.click()
        assertTrue(
            "message bubble should be gone after delete",
            waitUntilBlocking(10_000) { findBubbles(MESSAGES_PKG, MESSAGE_TEXT_RES, message).size <= bubblesBeforeSend }
        )
        sentMessage = null
        Log.i(TAG, "cleanup ok: message deleted")
    }

    @Test
    fun testSmsDraft_edit_send_andDelete(): Unit = runBlocking {
        assertTrue(
            "Real accessibility service must be connected for the screen agent to drive Messages",
            WhizAccessibilityService.isServiceConnected()
        )

        // ---- Launch in voice mode and capture the ChatViewModel ----
        voiceManager.setVoiceResponseEnabled(false)
        val chatViewModel = launchVoiceModeAndCaptureViewModel()

        // ---- Step 1: ask for the SMS; expect agent_draft_message with the overlay shown ----
        val mark1 = toolResults.size
        sendVoice(chatViewModel, "Hello, can you please send a text message to $smsRecipient that says $REQUESTED_BODY")
        val draft1 = awaitSuccess(DRAFT_TOOLS, mark1, DRAFT_TIMEOUT_MS, "sms_draft1")
        assertTrue("draft overlay not shown: ${draft1.result}", draft1.result.optBoolean("overlay_shown"))
        val draftText1 = draft1.result.optString("displayed_text")
        assertTrue("first draft text unexpected: '$draftText1'", draftText1.contains("testing SMS", ignoreCase = true))
        assertTrue("Messages app should be in the foreground", device.wait(Until.hasObject(By.pkg(MESSAGES_PKG).depth(0)), 10_000))
        assertTrue("overlay should report visible", MessageDraftOverlayService.overlayVisible)
        // Messages was force-stopped, so the first draft had to navigate; it reports the on-screen name.
        assertTrue("first draft should have navigated to the conversation: ${draft1.result}", draft1.result.optBoolean("chat_navigated"))
        assertTrue(
            "tool should report the conversation name shown on screen: ${draft1.result}",
            draft1.result.optString("chat_name_on_screen").contains(smsRecipient, ignoreCase = true)
        )
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 1 ok: draft='$draftText1'")

        // ---- Step 2: ask for a rewrite; expect a second draft with tracked-changes rendering ----
        val mark2 = toolResults.size
        sendVoice(chatViewModel, "Actually, can you make the message more polite?")
        val draft2 = awaitSuccess(DRAFT_TOOLS, mark2, DRAFT_TIMEOUT_MS, "sms_draft2")
        assertTrue("second draft overlay not shown: ${draft2.result}", draft2.result.optBoolean("overlay_shown"))
        assertFalse("redraft must not re-navigate to the conversation: ${draft2.result}", draft2.result.optBoolean("chat_navigated"))
        // "message" is the final text that will be sent; "displayed_text" is the tracked-changes
        // rendering of the overlay, with the removed characters still present (struck through).
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
        Log.i(TAG, "step 2 ok: message='$finalMessage' overlay='$draftText2' strikethrough=$struck colored=$colored")

        // ---- Step 3: confirm; expect the send tool, the overlay to go away, and a bubble in Messages ----
        // Count look-alike bubbles first (the other phone's test sends similar text here), so only a
        // bubble that appears after the send can satisfy the check.
        bubblesBeforeSend = findBubbles(MESSAGES_PKG, MESSAGE_TEXT_RES, finalMessage).size
        val mark3 = toolResults.size
        sendVoice(chatViewModel, "That looks good, go ahead and send the text.")
        val sent = awaitSuccess(SEND_TOOLS, mark3, SEND_TIMEOUT_MS, "sms_send")
        Log.i(TAG, "send tool result: ${sent.result}")
        sentMessage = finalMessage
        assertTrue("draft overlay should be dismissed after sending", waitUntil(10_000) { !MessageDraftOverlayService.isActive })

        val bubble = waitForSentBubble(MESSAGES_PKG, MESSAGE_TEXT_RES, finalMessage, 30_000, bubblesBeforeSend)
            ?: failWithScreenshot("Sent message '$finalMessage' did not appear as a new bubble in Messages", "sms_bubble_not_found")
        Log.i(TAG, "step 3 ok: bubble text='${bubble.text}'")

        // ---- Cleanup: delete the sent message through Messages' long-press menu ----
        deleteSentBubble(finalMessage)
    }
}
