package com.example.whiz.integration

import android.content.Intent
import android.os.Build
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.di.AppModule
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.MessageDraftOverlayService
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.tools.ToolExecutionResult
import com.example.whiz.tools.ToolExecutor
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/**
 * Instrumented version of the screen-agent SMS test (run_screen_agent_tests.py::test_sms_draft_message).
 *
 * Drives the same flow — draft an SMS, ask for it to be reworded, confirm sending — but asserts on
 * in-process signals instead of screenshots judged by a vision model:
 *
 *  - [ToolExecutor.toolResults] tells us exactly which tools ran and what they reported
 *    (overlay_shown, displayed_text), so the three overlay checks become exact assertions.
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
class SmsDraftMessageTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "SmsDraftMessageTest"
        private const val MESSAGES_PKG = "com.google.android.apps.messaging"
        // Messages' conversation screen is Compose; its bubbles expose the test tag "message_text"
        // as a bare resource id (no package prefix). Accept either form.
        private val MESSAGE_TEXT_RES: java.util.regex.Pattern = java.util.regex.Pattern.compile("(.*:id/)?message_text")

        // What the test asks for; the agent may reword it in step 2.
        private const val REQUESTED_BODY = "hey testing SMS from whiz voice"

        // The SMS flow uses the sms-specific tools; the generic ones are accepted too.
        private val DRAFT_TOOLS = setOf("agent_sms_draft_message", "agent_draft_message")
        private val SEND_TOOLS = setOf("agent_sms_send_message", "agent_send_message")

        private const val DRAFT_TIMEOUT_MS = 60_000L
        private const val SEND_TIMEOUT_MS = 60_000L

        @Volatile
        var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 2)
    val composeTestRule = createEmptyComposeRule()

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    override lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager

    @Inject
    lateinit var toolExecutor: ToolExecutor

    private val toolResults = CopyOnWriteArrayList<ToolExecutionResult>()
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null
    private val createdChatIds = mutableListOf<Long>()

    /** Same per-device mapping as run_screen_agent_tests.py: each phone messages the OTHER one. */
    private val smsRecipient: String
        get() = if (Build.MODEL.contains("Pixel 8")) "Whiz Voice Test" else "Ruth Grace Wong"

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        // toolResults is a SharedFlow with no replay, so start collecting before anything can run a tool.
        collectorJob = collectorScope.launch {
            toolExecutor.toolResults.collect { result ->
                Log.d(TAG, "tool result: ${describe(result)}")
                toolResults.add(result)
            }
        }
        // Start Messages from a known state, as the screen-agent test does.
        device.executeShellCommand("am force-stop $MESSAGES_PKG")
    }

    @After
    fun cleanup() {
        collectorJob?.cancel()
        collectorScope.cancel()
        runBlocking {
            try {
                if (BubbleOverlayService.isActive) {
                    BubbleOverlayService.stop(instrumentation.targetContext)
                }
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(REQUESTED_BODY, "send a text message to"),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
                ComposeTestHelper.cleanup()
            } catch (e: Exception) {
                Log.w(TAG, "cleanup failed", e)
            }
        }
        MainActivity.testViewModelCallback = null
        capturedViewModel = null
        try {
            device.executeShellCommand("am force-stop $MESSAGES_PKG")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testSmsDraft_edit_send_andDelete(): Unit = runBlocking {
        assertTrue(
            "Real accessibility service must be connected for the screen agent to drive Messages",
            WhizAccessibilityService.isServiceConnected()
        )

        // ---- Launch in voice mode and capture the ChatViewModel (same as VoiceControlToolsTest) ----
        voiceManager.setVoiceResponseEnabled(false)
        voiceManager.ttsStateBeforeBackground = null
        capturedViewModel = null
        MainActivity.testViewModelCallback = { vm -> capturedViewModel = vm }
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or 0x10000000
                putExtra("tracing_intent_id", System.currentTimeMillis())
            }
        )
        if (!device.wait(Until.hasObject(By.clazz("android.widget.EditText").pkg(packageName)), 10_000)) {
            failWithScreenshot("sms_voice_launch_failed", "Voice launch did not reach a chat screen")
        }
        waitUntil(5_000) { capturedViewModel != null }
        val chatViewModel = capturedViewModel
            ?: failWithScreenshot("sms_viewmodel_not_captured", "ChatViewModel not captured")
        chatViewModel.chatId.value?.let { if (it > 0 && it !in createdChatIds) createdChatIds.add(it) }

        // ---- Step 1: ask for the SMS; expect agent_draft_message with the overlay shown ----
        val mark1 = toolResults.size
        sendVoice(chatViewModel, "Hello, can you please send a text message to $smsRecipient that says $REQUESTED_BODY")
        val draft1 = awaitSuccess(DRAFT_TOOLS, mark1, DRAFT_TIMEOUT_MS)
            ?: failWithScreenshot("sms_no_draft_tool_result", "No agent_draft_message result within ${DRAFT_TIMEOUT_MS}ms")
        assertTrue("draft overlay not shown: ${draft1.result}", draft1.result.optBoolean("overlay_shown"))
        val draftText1 = draft1.result.optString("displayed_text")
        assertTrue("first draft text unexpected: '$draftText1'", draftText1.contains("testing SMS", ignoreCase = true))
        assertTrue("Messages app should be in the foreground", device.wait(Until.hasObject(By.pkg(MESSAGES_PKG).depth(0)), 10_000))
        assertTrue("overlay should report visible", MessageDraftOverlayService.overlayVisible)
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 1 ok: draft='$draftText1'")

        // ---- Step 2: ask for a rewrite; expect a second draft with tracked-changes rendering ----
        val mark2 = toolResults.size
        sendVoice(chatViewModel, "Actually, can you make the message more polite?")
        val draft2 = awaitSuccess(DRAFT_TOOLS, mark2, DRAFT_TIMEOUT_MS)
            ?: failWithScreenshot("sms_no_second_draft", "No second agent_draft_message result within ${DRAFT_TIMEOUT_MS}ms")
        assertTrue("second draft overlay not shown: ${draft2.result}", draft2.result.optBoolean("overlay_shown"))
        // "message" is the final text that will be sent; "displayed_text" is the tracked-changes
        // rendering of the overlay, with the removed characters still present (struck through).
        val finalMessage = draft2.result.optString("message")
        val draftText2 = draft2.result.optString("displayed_text")
        assertNotEquals("rewrite should change the message", draftText1, finalMessage)
        assertEquals("overlay's displayed text should match the tool result", draftText2, MessageDraftOverlayService.displayedDraftText)
        val (struck, colored) = waitForTrackedChangesSpans(10_000)
        assertTrue("expected strikethrough span(s) for removed text in the overlay", struck > 0)
        assertTrue("expected colored span(s) (removed/added) in the overlay", colored > 0)
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 2 ok: message='$finalMessage' overlay='$draftText2' strikethrough=$struck colored=$colored")

        // ---- Step 3: confirm; expect the send tool, the overlay to go away, and a bubble in Messages ----
        val mark3 = toolResults.size
        sendVoice(chatViewModel, "That looks good, go ahead and send the text.")
        val sent = awaitSuccess(SEND_TOOLS, mark3, SEND_TIMEOUT_MS)
            ?: failWithScreenshot("sms_no_send_tool_result", "No send tool result within ${SEND_TIMEOUT_MS}ms")
        Log.i(TAG, "send tool result: ${sent.result}")
        assertTrue("draft overlay should be dismissed after sending", waitUntil(10_000) { !MessageDraftOverlayService.isActive })

        val bubble = waitForSentBubble(finalMessage, 30_000)
            ?: failWithScreenshot("sms_bubble_not_found", "Sent message '$finalMessage' did not appear as a bubble in Messages")
        Log.i(TAG, "step 3 ok: bubble text='${bubble.text}'")

        // ---- Cleanup: delete the sent message through Messages' long-press menu ----
        bubble.longClick()
        val deleteItem = device.wait(Until.findObject(By.pkg(MESSAGES_PKG).text("Delete")), 5_000)
            ?: failWithScreenshot("sms_no_delete_menu", "Long-press menu with 'Delete' did not appear")
        deleteItem.click()
        val confirmTitle = device.wait(Until.hasObject(By.pkg(MESSAGES_PKG).textStartsWith("Delete this message")), 5_000)
        assertTrue("delete confirmation dialog did not appear", confirmTitle)
        val confirmButton = device.findObjects(By.pkg(MESSAGES_PKG).text("Delete")).lastOrNull()
            ?: failWithScreenshot("sms_no_delete_confirm", "Delete confirmation button not found")
        confirmButton.click()
        assertTrue(
            "message bubble should be gone after delete",
            device.wait(Until.gone(By.res(MESSAGE_TEXT_RES).textContains(distinctiveFragment(finalMessage))), 10_000)
        )
        Log.i(TAG, "cleanup ok: message deleted")
    }

    // ------------------------------------------------------------------------------------------

    private fun sendVoice(chatViewModel: com.example.whiz.ui.viewmodels.ChatViewModel, text: String) {
        Log.i(TAG, "voice: '$text'")
        val ok = simulateVoiceTranscriptionAndSend(
            message = text,
            rapid = true,
            chatViewModel = chatViewModel,
            speechRecognitionService = null,
            composeTestRule = composeTestRule
        )
        assertTrue("failed to send voice transcript '$text'", ok)
    }

    /**
     * A tool result arrives before the assistant's text for that turn. Sending the next request
     * while the previous turn is still open makes the server treat it as an interruption (it
     * answered "How would you like me to reword it?" instead of redrafting). So wait for the
     * turn to close, plus a short settle, before the next message.
     */
    private suspend fun awaitTurnComplete(chatViewModel: com.example.whiz.ui.viewmodels.ChatViewModel, timeoutMs: Long = 30_000) {
        val closed = waitUntil(timeoutMs) { !chatViewModel.isResponding.value }
        assertTrue("assistant turn did not complete within ${timeoutMs}ms", closed)
        delay(2_000)
    }

    /** First Success result for one of [toolNames] emitted after index [after]. */
    private suspend fun awaitSuccess(toolNames: Set<String>, after: Int, timeoutMs: Long): ToolExecutionResult.Success? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = toolResults.toList()
            for (i in after until snapshot.size) {
                when (val r = snapshot[i]) {
                    is ToolExecutionResult.Success -> if (r.toolName in toolNames) return r
                    is ToolExecutionResult.Error -> if (r.toolName in toolNames) {
                        failWithScreenshot("sms_tool_error_${r.toolName}", "${r.toolName} failed: ${r.error}")
                    }
                    else -> { /* progress/status updates are not terminal */ }
                }
            }
            delay(250)
        }
        return null
    }

    /**
     * Reads the draft overlay's TextView through the accessibility tree and counts the
     * StrikethroughSpan / ForegroundColorSpan instances on its text.
     */
    private fun waitForTrackedChangesSpans(timeoutMs: Long): Pair<Int, Int> {
        // Request UiAutomation with the same flags UiDevice uses, so this does not reconnect and
        // flip accessibility suppression back on (see BaseIntegrationTest.setUp).
        val uiAutomation = instrumentation.getUiAutomation(Configurator.getInstance().uiAutomationFlags)
        val viewId = "$packageName:id/draft_message_text"
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Pair<Int, Int> = 0 to 0
        while (System.currentTimeMillis() < deadline) {
            device.waitForIdle(1_000)
            val node: AccessibilityNodeInfo? = uiAutomation.windows
                .asSequence()
                .mapNotNull { it.root }
                .flatMap { root -> root.findAccessibilityNodeInfosByViewId(viewId).asSequence() }
                .firstOrNull()
            val text = node?.text
            if (text is Spanned && text.isNotEmpty()) {
                last = text.getSpans(0, text.length, StrikethroughSpan::class.java).size to
                    text.getSpans(0, text.length, ForegroundColorSpan::class.java).size
                if (last.first > 0 && last.second > 0) return last
            }
            Thread.sleep(500)
        }
        return last
    }

    /** A message bubble (not the compose field) in Messages whose text contains the sent message. */
    private fun waitForSentBubble(fullText: String, timeoutMs: Long): UiObject2? {
        val fragment = distinctiveFragment(fullText)
        val selector = By.pkg(MESSAGES_PKG).res(MESSAGE_TEXT_RES).textContains(fragment)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val candidate = device.findObjects(selector).firstOrNull { it.className != "android.widget.EditText" }
            if (candidate != null) return candidate
            Thread.sleep(500)
        }
        return null
    }

    /** A chunk of the message long enough to be unique but short enough to survive line wrapping. */
    private fun distinctiveFragment(text: String): String = text.trim().take(24)

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(200)
        }
        return condition()
    }

    private fun describe(r: ToolExecutionResult) = when (r) {
        is ToolExecutionResult.Success -> "${r.toolName} ok ${r.result}"
        is ToolExecutionResult.Error -> "${r.toolName} error ${r.error}"
        else -> r.toString()
    }
}
