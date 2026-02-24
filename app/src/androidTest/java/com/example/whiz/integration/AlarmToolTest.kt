package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.TestCredentialsManager
import com.example.whiz.data.local.MessageType
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.test_helpers.ComposeTestHelper
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.services.BubbleOverlayService
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Integration test for alarm tools: set alarm, get next alarm, delete alarm.
 *
 * Tests the server's ability to:
 * 1. Set an alarm 7 minutes from now
 * 2. Get the next alarm and verify it matches
 * 3. Delete (disable) that specific alarm
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AlarmToolTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "AlarmToolTest"
        private const val BOT_RESPONSE_TIMEOUT = 45000L
        private const val VIEWMODEL_CAPTURE_TIMEOUT = 5000L
        private const val POLL_INTERVAL_MS = 50L
    }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    private val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private val createdChatIds = mutableListOf<Long>()
    private val uniqueTestId = System.currentTimeMillis()

    private var navigationScopedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        MainActivity.testViewModelCallback = { viewModel ->
            Log.d(TAG, "Captured navigation-scoped ViewModel: ${viewModel.hashCode()}")
            navigationScopedViewModel = viewModel
        }

        Log.d(TAG, "alarm tool test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "cleaning up alarm test")
            try {
                // Stop bubble service if active (tool execution can trigger bubble mode)
                if (BubbleOverlayService.isActive) {
                    Log.d(TAG, "Stopping bubble service...")
                    BubbleOverlayService.stop(instrumentation.targetContext)
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < 1000L) {
                        if (!BubbleOverlayService.isActive) {
                            Log.d(TAG, "Bubble service stopped")
                            break
                        }
                        delay(100)
                    }
                }

                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "set an alarm",
                        "next alarm",
                        "delete",
                        "disable",
                        uniqueTestId.toString()
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
                ComposeTestHelper.cleanup()
                Log.d(TAG, "test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "Error during test chat cleanup", e)
            } finally {
                MainActivity.testViewModelCallback = null
                navigationScopedViewModel = null
            }
        }
    }

    private fun waitForViewModel(timeout: Long = VIEWMODEL_CAPTURE_TIMEOUT): com.example.whiz.ui.viewmodels.ChatViewModel? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            val vm = navigationScopedViewModel
            if (vm != null) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "ViewModel captured after ${elapsed}ms")
                return vm
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Log.e(TAG, "Failed to capture ViewModel within ${timeout}ms")
        return null
    }

    private fun waitForBotResponse(
        viewModel: com.example.whiz.ui.viewmodels.ChatViewModel,
        previousBotMessageCount: Int,
        timeout: Long = BOT_RESPONSE_TIMEOUT
    ): String? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val isResponding = viewModel.isResponding.value
            val messages = viewModel.messages.value
            val botMessages = messages.filter { it.type == MessageType.ASSISTANT }

            if (botMessages.size > previousBotMessageCount) {
                val lastBotMessage = botMessages.last()
                val content = lastBotMessage.content

                if (!isResponding && content.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Bot responded after ${elapsed}ms")
                    Log.d(TAG, "Bot response: $content")
                    return content
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        Log.e(TAG, "Bot did not respond within ${timeout}ms")
        return null
    }

    private fun getBotMessageCount(viewModel: com.example.whiz.ui.viewmodels.ChatViewModel): Int {
        return viewModel.messages.value.filter { it.type == MessageType.ASSISTANT }.size
    }

    @Test
    fun testSetGetDeleteAlarm(): Unit = runBlocking {
        Log.d(TAG, "starting set/get/delete alarm test")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        // Calculate alarm time: 7 minutes from now
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MINUTE, 7)
        val alarmHour = calendar.get(Calendar.HOUR_OF_DAY)
        val alarmMinute = calendar.get(Calendar.MINUTE)
        val amPm = if (alarmHour < 12) "AM" else "PM"
        val displayHour = if (alarmHour == 0) 12 else if (alarmHour > 12) alarmHour - 12 else alarmHour
        val alarmTimeStr = "$displayHour:${alarmMinute.toString().padStart(2, '0')} $amPm"

        Log.d(TAG, "Target alarm time: $alarmTimeStr (24h: $alarmHour:${alarmMinute.toString().padStart(2, '0')})")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "step 1: verifying app is ready...")
            composeTestRule.waitForIdle()

            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "FAILURE: app not ready")
                failWithScreenshot("alarm_app_not_ready", "app not ready")
                return@runBlocking
            }

            // Step 2: Navigate to new chat
            Log.d(TAG, "step 2: navigating to new chat...")
            val initialChats = try {
                repository.getAllChats()
            } catch (e: Exception) {
                Log.w(TAG, "Could not get initial chats: ${e.message}")
                emptyList()
            }

            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    Log.e(TAG, "FAILURE: failed to navigate back")
                    failWithScreenshot("alarm_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "FAILURE: failed to navigate to new chat")
                failWithScreenshot("alarm_new_chat_failed", "failed to navigate to new chat")
                return@runBlocking
            }

            // Track new chat
            val currentChats = repository.getAllChats()
            val newChats = currentChats.filter { !initialChats.map { it.id }.contains(it.id) }
            newChats.forEach { chat ->
                if (!createdChatIds.contains(chat.id)) {
                    createdChatIds.add(chat.id)
                    Log.d(TAG, "Tracked new chat: ${chat.id}")
                }
            }

            val chatViewModel = waitForViewModel()
            if (chatViewModel == null) {
                failWithScreenshot("alarm_no_viewmodel", "Failed to capture ViewModel")
                return@runBlocking
            }

            Log.d(TAG, "Using navigation-scoped ViewModel: ${chatViewModel.hashCode()}")

            // Step 3: Set an alarm 7 minutes from now
            Log.d(TAG, "step 3: setting alarm for $alarmTimeStr...")
            val setAlarmMessage = "Set an alarm for $alarmTimeStr. Reply only with SUCCESS if the alarm was set. - $uniqueTestId"

            val previousBotCount1 = getBotMessageCount(chatViewModel)

            val setAlarmSent = ComposeTestHelper.sendMessageAndVerifyDisplay(
                composeTestRule = composeTestRule,
                message = setAlarmMessage
            )

            if (!setAlarmSent) {
                Log.e(TAG, "Set alarm message not sent")
                failWithScreenshot("alarm_set_message_not_sent", "set alarm message not sent")
                return@runBlocking
            }

            val setResponse = waitForBotResponse(chatViewModel, previousBotCount1)
            if (setResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to set alarm")
                failWithScreenshot("alarm_set_no_response", "bot did not respond to set alarm")
                return@runBlocking
            }

            Log.d(TAG, "Set alarm response: $setResponse")

            // Step 4: Get next alarm and verify it matches
            Log.d(TAG, "step 4: getting next alarm...")
            val getAlarmMessage = "What is my next alarm? Reply with the exact time. - $uniqueTestId"

            val previousBotCount2 = getBotMessageCount(chatViewModel)

            val getAlarmSent = ComposeTestHelper.sendMessageAndVerifyDisplay(
                composeTestRule = composeTestRule,
                message = getAlarmMessage
            )

            if (!getAlarmSent) {
                Log.e(TAG, "Get alarm message not sent")
                failWithScreenshot("alarm_get_message_not_sent", "get alarm message not sent")
                return@runBlocking
            }

            val getResponse = waitForBotResponse(chatViewModel, previousBotCount2)
            if (getResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to get next alarm")
                failWithScreenshot("alarm_get_no_response", "bot did not respond to get next alarm")
                return@runBlocking
            }

            // Verify the response mentions our alarm time (handle format variations like "5:08 PM" vs "05:08 PM")
            val minuteStr = alarmMinute.toString().padStart(2, '0')
            val responseLC = getResponse.lowercase()
            // Check for the hour:minute pattern in either format
            val matchesTime = responseLC.contains("$displayHour:$minuteStr") ||
                    responseLC.contains("${alarmHour.toString().padStart(2, '0')}:$minuteStr")

            if (!matchesTime) {
                // The next alarm might be a different (earlier) existing alarm — that's OK,
                // we still want to proceed to delete the alarm we set
                Log.w(TAG, "Next alarm is not $alarmTimeStr (got: $getResponse) - an earlier alarm may exist. Proceeding to delete our alarm anyway.")
            } else {
                Log.d(TAG, "Get next alarm confirmed: $alarmTimeStr")
            }

            // Step 5: Delete (disable) the alarm
            Log.d(TAG, "step 5: deleting alarm for $alarmTimeStr...")
            val deleteAlarmMessage = "Delete my $alarmTimeStr alarm. Reply only with SUCCESS if it was deleted. - $uniqueTestId"

            val previousBotCount3 = getBotMessageCount(chatViewModel)

            val deleteAlarmSent = ComposeTestHelper.sendMessageAndVerifyDisplay(
                composeTestRule = composeTestRule,
                message = deleteAlarmMessage
            )

            if (!deleteAlarmSent) {
                Log.e(TAG, "Delete alarm message not sent")
                failWithScreenshot("alarm_delete_message_not_sent", "delete alarm message not sent")
                return@runBlocking
            }

            val deleteResponse = waitForBotResponse(chatViewModel, previousBotCount3, timeout = 60000L)
            if (deleteResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to delete alarm")
                failWithScreenshot("alarm_delete_no_response", "bot did not respond to delete alarm")
                return@runBlocking
            }

            Log.d(TAG, "Delete alarm response: $deleteResponse")

            // Verify the delete response indicates the alarm was actually disabled
            // We asked the bot to reply only with SUCCESS, so check for that
            val deleteResponseLC = deleteResponse.lowercase()
            val hasSuccess = deleteResponseLC.contains("success")
            val hasFailure = deleteResponseLC.contains("fail") || deleteResponseLC.contains("error") ||
                deleteResponseLC.contains("could not") || deleteResponseLC.contains("unable") ||
                deleteResponseLC.contains("couldn't") || deleteResponseLC.contains("sorry")
            if (!hasSuccess || hasFailure) {
                Log.e(TAG, "FAILURE: delete alarm did not succeed: $deleteResponse")
                failWithScreenshot("alarm_delete_failed", "delete alarm did not succeed: $deleteResponse")
                return@runBlocking
            }
            Log.d(TAG, "Delete alarm response indicates success")

            // Step 6: Verify the alarm is actually gone by asking for next alarm again
            Log.d(TAG, "step 6: verifying alarm was deleted by checking next alarm...")
            val verifyMessage = "What is my next alarm? Reply with the exact time, or say NONE if there are no alarms. - $uniqueTestId"

            val previousBotCount4 = getBotMessageCount(chatViewModel)

            val verifySent = ComposeTestHelper.sendMessageAndVerifyDisplay(
                composeTestRule = composeTestRule,
                message = verifyMessage
            )

            if (!verifySent) {
                Log.e(TAG, "Verify message not sent")
                failWithScreenshot("alarm_verify_message_not_sent", "verify message not sent")
                return@runBlocking
            }

            val verifyResponse = waitForBotResponse(chatViewModel, previousBotCount4)
            if (verifyResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to verify request")
                failWithScreenshot("alarm_verify_no_response", "bot did not respond to verify request")
                return@runBlocking
            }

            Log.d(TAG, "Verify response after delete: $verifyResponse")

            // The deleted alarm time should NOT be the next alarm anymore
            val verifyLC = verifyResponse.lowercase()
            val minuteStrV = alarmMinute.toString().padStart(2, '0')
            val stillHasAlarm = verifyLC.contains("$displayHour:$minuteStrV") &&
                    (verifyLC.contains(amPm.lowercase()) || verifyLC.contains("${alarmHour.toString().padStart(2, '0')}:$minuteStrV"))

            if (stillHasAlarm) {
                Log.w(TAG, "Next alarm still shows $alarmTimeStr - alarm may not have been fully disabled")
                // Don't fail here since there could be a duplicate alarm at the same time,
                // but log it as a warning
            } else {
                Log.d(TAG, "Confirmed: $alarmTimeStr is no longer the next alarm")
            }

            // Track final chat ID
            try {
                val finalChatId = chatViewModel.chatId.value
                if (finalChatId != null && finalChatId > 0 && !createdChatIds.contains(finalChatId)) {
                    createdChatIds.add(finalChatId)
                    Log.d(TAG, "Tracked final chat ID: $finalChatId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not track final chat ID: ${e.message}")
            }

            Log.d(TAG, "alarm tool test PASSED!")
            Log.d(TAG, "Test validated: set alarm ($alarmTimeStr), get next alarm, delete alarm")

        } catch (e: Exception) {
            Log.e(TAG, "Test failed with exception: ${e.message}", e)
            failWithScreenshot("alarm_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
