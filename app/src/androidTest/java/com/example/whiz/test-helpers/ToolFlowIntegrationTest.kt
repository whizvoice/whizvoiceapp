package com.example.whiz

import android.content.Intent
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.MessageDraftOverlayService
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.tools.ToolExecutionResult
import com.example.whiz.tools.ToolExecutor
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.viewmodels.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Base class for the screen agent tests (SMS, WhatsApp, YouTube Music, Google Maps, media duck).
 *
 * A "tool flow" test launches Whiz in voice mode, sends voice transcriptions the way a user would,
 * and asserts on in-process signals ([ToolExecutor.toolResults], the draft overlay's
 * accessibility text) plus the third-party app's UI through UiDevice. This class owns the pieces
 * every such test needs; see SmsDraftMessageTest for the canonical use.
 *
 * Concrete subclasses still carry `@UninstallModules(AppModule::class)`, `@HiltAndroidTest` and
 * `@RunWith(AndroidJUnit4::class)` themselves (Hilt requires them on the test class).
 */
abstract class ToolFlowIntegrationTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "ToolFlowIntegrationTest"
        private const val PERMISSION_CONTROLLER_PKG = "com.google.android.permissioncontroller"

        @Volatile
        private var capturedViewModel: ChatViewModel? = null
    }

    protected val instrumentation = InstrumentationRegistry.getInstrumentation()

    // No ComposeTestRule here, deliberately. These tests let the real app run on its own clock and
    // only observe it. With a ComposeTestRule active (a) UiDevice taps on Whiz's Compose UI are
    // received but never recomposed unless the test pumps waitForIdle(), and (b) the rule's test frame
    // clock can run a measure pass while the real view is mid-layout and crash the whole test with
    // "performMeasureAndLayout called during measure layout" (seen on Android 17). sendVoice uses the
    // direct ChatViewModel path with rapid = true, which never touches the rule.

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    override lateinit var voiceManager: VoiceManager

    @Inject
    lateinit var toolExecutor: ToolExecutor

    /** Every [ToolExecutionResult] emitted since [startToolResultCollector], in order. */
    protected val toolResults = CopyOnWriteArrayList<ToolExecutionResult>()

    /** Chat ids this test created; deleted by [cleanupTrackedChats]. */
    protected val createdChatIds = mutableListOf<String>()

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    // ---------------------------------------------------------------- tool results

    private var savedWaitForIdleTimeoutMs = -1L

    /** toolResults is a SharedFlow with no replay: call this from @Before, before anything can run a tool. */
    protected fun startToolResultCollector() {
        // Every UiDevice lookup first waits for the UI to go idle, 10s by default. A playing video ad
        // or an animating player (YouTube Music) never idles, so each poll silently blocked for 10s and
        // a 10s wait loop took minutes (2026-11-23). These tests poll on their own clock; cap the wait.
        val configurator = Configurator.getInstance()
        savedWaitForIdleTimeoutMs = configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout = 1_000
        collectorJob = collectorScope.launch {
            toolExecutor.toolResults.collect { result ->
                Log.d(TAG, "tool result: ${describe(result)}")
                toolResults.add(result)
            }
        }
    }

    protected fun stopToolResultCollector() {
        collectorJob?.cancel()
        collectorJob = null
        if (savedWaitForIdleTimeoutMs >= 0) {
            Configurator.getInstance().waitForIdleTimeout = savedWaitForIdleTimeoutMs
            savedWaitForIdleTimeoutMs = -1
        }
    }

    /**
     * First Success result for one of [toolNames] at index >= [fromIndex]. An Error for one of those
     * tools fails immediately (with a screenshot), as does the timeout; Status results are skipped.
     * Several tools (YouTube Music, Maps) report action failure as a Success whose JSON carries
     * `"success": false` plus an `error`; that is treated as a failure too.
     *
     * Failing on the first `success: false` is deliberate. In production such a result now carries a
     * `stuck` block and the model may clear the obstacle and retry; these tests cover the scripted
     * flows themselves, so a script that needed rescuing is still a failing script here.
     */
    protected suspend fun awaitSuccess(
        toolNames: Set<String>,
        fromIndex: Int,
        timeoutMs: Long,
        screenshotTag: String
    ): ToolExecutionResult.Success {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = toolResults.toList()
            for (i in fromIndex until snapshot.size) {
                when (val r = snapshot[i]) {
                    is ToolExecutionResult.Success -> if (r.toolName in toolNames) {
                        if (r.result.has("success") && !r.result.optBoolean("success", true)) {
                            failWithScreenshot("${r.toolName} reported failure: ${r.result.optString("error", r.result.toString())}", "${screenshotTag}_tool_failed")
                        }
                        return r
                    }
                    is ToolExecutionResult.Error -> if (r.toolName in toolNames) {
                        failWithScreenshot("${r.toolName} failed: ${r.error}", "${screenshotTag}_tool_error")
                    }
                    else -> { /* progress/status updates are not terminal */ }
                }
            }
            delay(250)
        }
        failWithScreenshot("No $toolNames result within ${timeoutMs}ms", "${screenshotTag}_no_tool_result")
    }

    /**
     * First Success result for [toolName] at index >= [fromIndex], whatever its `success` field says.
     * For tests that deliberately drive a tool into failure and inspect the failure payload.
     */
    protected suspend fun awaitResult(
        toolName: String,
        fromIndex: Int,
        timeoutMs: Long,
        screenshotTag: String
    ): ToolExecutionResult.Success {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = toolResults.toList()
            for (i in fromIndex until snapshot.size) {
                val r = snapshot[i]
                if (r is ToolExecutionResult.Success && r.toolName == toolName) return r
                if (r is ToolExecutionResult.Error && r.toolName == toolName) {
                    failWithScreenshot("$toolName threw instead of reporting a result: ${r.error}", "${screenshotTag}_tool_error")
                }
            }
            delay(250)
        }
        failWithScreenshot("No $toolName result within ${timeoutMs}ms", "${screenshotTag}_no_tool_result")
    }

    // ---------------------------------------------------------------- launch + voice

    /**
     * Launches MainActivity with the voice-mode intent (new chat + mic), captures the ChatViewModel
     * through [MainActivity.testViewModelCallback], and tracks the new chat id for cleanup.
     */
    protected fun launchVoiceModeAndCaptureViewModel(): ChatViewModel {
        device.executeShellCommand("cmd statusbar collapse") // a pulled-down shade hides the chat screen
        voiceManager.ttsStateBeforeBackground = null
        captureChatViewModel()
        // CLEAR_TASK: a MainActivity left over from a previous test class holds that test's Hilt
        // component, including a different ToolExecutor whose toolResults this test never sees.
        // A fresh activity is built from the current component, the one `toolExecutor` was injected from.
        instrumentation.targetContext.startActivity(
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or 0x10000000
                putExtra("tracing_intent_id", System.currentTimeMillis())
            }
        )
        if (!device.wait(Until.hasObject(By.clazz("android.widget.EditText").pkg(packageName)), 10_000)) {
            failWithScreenshot("Voice launch did not reach a chat screen", "voice_launch_failed")
        }
        waitUntilBlocking(5_000) { capturedViewModel != null }
        // The chat id is read in cleanupTrackedChats, not here: the Activity-scoped ChatViewModel
        // still carries the previous conversation's id until loadChat finishes.
        return capturedViewModel
            ?: failWithScreenshot("ChatViewModel not captured", "viewmodel_not_captured")
    }

    protected fun sendVoice(chatViewModel: ChatViewModel, text: String) {
        Log.i(TAG, "voice: '$text'")
        val ok = simulateVoiceTranscriptionAndSend(
            message = text,
            rapid = true, // the rapid path never needs a ComposeTestRule (see the class note above)
            chatViewModel = chatViewModel,
            speechRecognitionService = null,
            composeTestRule = null
        )
        assertTrue("failed to send voice transcript '$text'", ok)
    }

    /**
     * A tool result arrives before the assistant's text for that turn. Sending the next request
     * while the previous turn is still open makes the server treat it as an interruption (it
     * answered "How would you like me to reword it?" instead of redrafting). So wait for the
     * turn to close, plus a short settle, before the next message.
     */
    protected suspend fun awaitTurnComplete(chatViewModel: ChatViewModel, timeoutMs: Long = 30_000) {
        val closed = waitUntil(timeoutMs) { !chatViewModel.isResponding.value }
        assertTrue("assistant turn did not complete within ${timeoutMs}ms", closed)
        delay(2_000)
    }

    // ---------------------------------------------------------------- overlay + third-party UI

    /**
     * Reads the draft overlay's TextView through the accessibility tree and returns
     * (StrikethroughSpan count, ForegroundColorSpan count); waits until both are > 0 or [timeoutMs].
     */
    protected fun waitForTrackedChangesSpans(timeoutMs: Long): Pair<Int, Int> {
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

    /** Message bubbles (not the compose field) in [pkg] whose resource id matches and whose text contains [text]. */
    protected fun findBubbles(pkg: String, resIdPattern: Pattern, text: String): List<UiObject2> =
        device.findObjects(By.pkg(pkg).res(resIdPattern).textContains(distinctiveFragment(text)))
            .filter { it.className != "android.widget.EditText" }

    /**
     * The bottom-most matching bubble once there are more than [countBefore] of them, i.e. a bubble
     * that appeared after the caller counted. Older or incoming bubbles with the same opening words
     * cannot satisfy this; look the object up again right before acting on it, it goes stale as the
     * list rebinds.
     */
    protected fun waitForSentBubble(pkg: String, resIdPattern: Pattern, text: String, timeoutMs: Long, countBefore: Int = 0): UiObject2? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val bubbles = findBubbles(pkg, resIdPattern, text)
            if (bubbles.size > countBefore) return bubbles.maxByOrNull { it.visibleBounds.top }
            Thread.sleep(500)
        }
        return null
    }

    /** A chunk of the message long enough to be unique but short enough to survive line wrapping. */
    protected fun distinctiveFragment(text: String): String = text.trim().take(24)

    // ---------------------------------------------------------------- waiting + cleanup

    protected suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(200)
        }
        return condition()
    }

    protected fun waitUntilBlocking(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }

    /**
     * Denies an Android runtime-permission prompt raised by the third-party app under test, if one
     * is on screen (e.g. YouTube Music asking for nearby devices after an update). The dialog is a
     * separate window owned by the permission controller, so the app's own UI is unreachable behind
     * it. Deny rather than allow: a test should not hand permissions to someone else's app.
     * Returns true if a prompt was dismissed.
     */
    protected fun dismissPermissionPromptIfShown(): Boolean {
        val deny = By.pkg(PERMISSION_CONTROLLER_PKG).res(Pattern.compile(".*:id/permission_deny_button"))
        val button = device.findObject(deny) ?: return false
        Log.i(TAG, "dismissing permission prompt: '${device.findObject(By.pkg(PERMISSION_CONTROLLER_PKG).res(Pattern.compile(".*:id/permission_message")))?.text}'")
        button.click()
        device.wait(Until.gone(deny), 3_000)
        return true
    }

    /** Stops the bubble and any draft overlay a failed step may have left on screen. */
    protected fun stopBubbleOverlayIfActive() {
        if (BubbleOverlayService.isActive) {
            BubbleOverlayService.stop(instrumentation.targetContext)
        }
        if (MessageDraftOverlayService.isActive) {
            MessageDraftOverlayService.stop(instrumentation.targetContext)
        }
    }

    /**
     * Starts observing [MainActivity.testViewModelCallback] so the chat this test opens (by voice
     * launch or by tapping New Chat) is known by id and can be deleted in [cleanupTrackedChats].
     */
    protected fun captureChatViewModel() {
        capturedViewModel = null
        MainActivity.testViewModelCallback = { vm -> capturedViewModel = vm }
    }

    /**
     * Deletes only the chats this test created: the tracked ids plus the captured view model's chat.
     * Deliberately no title-pattern fallback. The account is shared with CI and the other phone, and a
     * pattern match would delete a chat another run is in the middle of using (seen 2026-11-12: CI's
     * cleanup soft-deleted the phone's live WhatsApp and Maps chats mid-turn).
     */
    protected suspend fun cleanupTrackedChats() {
        try {
            // By now loadChat has long finished, so the captured view model's id is the test's own chat.
            capturedViewModel?.chatId?.value?.let { if (it.isNotBlank() && it !in createdChatIds) createdChatIds.add(it) }
            cleanupTestChats(
                repository = repository,
                trackedChatIds = createdChatIds,
                additionalPatterns = emptyList(),
                enablePatternFallback = false
            )
            createdChatIds.clear()
            ComposeTestHelper.cleanup()
        } catch (e: Exception) {
            Log.w(TAG, "chat cleanup failed", e)
        }
        MainActivity.testViewModelCallback = null
        capturedViewModel = null
    }

    private fun describe(r: ToolExecutionResult) = when (r) {
        is ToolExecutionResult.Success -> "${r.toolName} ok ${r.result}"
        is ToolExecutionResult.Error -> "${r.toolName} error ${r.error}"
        else -> r.toString()
    }
}
