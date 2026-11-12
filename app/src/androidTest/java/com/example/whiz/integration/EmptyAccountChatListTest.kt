package com.example.whiz.integration

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.viewmodels.VoiceManager
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Regression test for an account with zero chats: the empty state must show (not "Loading chats..."),
 * and a chat created from it must appear in the list when the user navigates back.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EmptyAccountChatListTest : BaseIntegrationTest() {

    @Inject
    override lateinit var voiceManager: VoiceManager

    @Inject
    override lateinit var speechRecognitionService: SpeechRecognitionService

    @Inject
    lateinit var repository: com.example.whiz.data.repository.WhizRepository

    private val TAG = "EmptyAccountChatListTest"
    private val createdChatIds = mutableListOf<String>()
    private var navigationScopedViewModel: ChatViewModel? = null

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        MainActivity.testViewModelCallback = { viewModel ->
            navigationScopedViewModel = viewModel
        }

        // Make the account empty
        runBlocking {
            repository.listChats().forEach { repository.deleteChatById(it.id) }
            assertTrue("test account must be empty", repository.listChats().isEmpty())
        }
        device.pressHome()
        Thread.sleep(1000)
    }

    @After
    fun cleanup() {
        runBlocking {
            try {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf("EMPTY_ACCOUNT_TEST_"),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed", e)
            }
        }
        MainActivity.testViewModelCallback = null
        navigationScopedViewModel = null
    }

    @Test
    fun realApp_emptyAccount_createChat_back_rowVisible() {
        if (!launchAppAndWaitForLoad()) {
            failWithScreenshot("App failed to launch")
        }

        // Empty state is shown, not the loading placeholder
        val emptyShown = device.wait(Until.hasObject(By.text("No chats yet").pkg(packageName)), 15_000)
        if (!emptyShown) {
            failWithScreenshot("empty_state_not_shown", "Expected 'No chats yet' on an account with no chats")
        }
        assertTrue(
            "'Loading chats...' must not be shown once the empty state is displayed",
            !device.hasObject(By.text("Loading chats...").pkg(packageName))
        )

        // Create a chat
        if (!clickNewChatButtonAndWaitForChatScreen()) {
            failWithScreenshot("new_chat_failed", "Could not open a new chat from the empty list")
        }

        val message = "EMPTY_ACCOUNT_TEST_${System.currentTimeMillis()}"
        val viewModel = waitForViewModel()
        if (viewModel == null) {
            failWithScreenshot("no_viewmodel", "Could not capture the navigation-scoped ChatViewModel")
        }
        simulateVoiceTranscriptionAndSend(message, rapid = true, chatViewModel = viewModel)

        // Track the created chat for cleanup
        runBlocking {
            val trackStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - trackStart < 10_000) {
                val chats = repository.observeChats().first()
                if (chats.isNotEmpty()) {
                    chats.forEach { if (!createdChatIds.contains(it.id)) createdChatIds.add(it.id) }
                    break
                }
                kotlinx.coroutines.delay(200)
            }
        }

        // Wait for the response to finish
        val start = System.currentTimeMillis()
        var sawResponding = false
        while (System.currentTimeMillis() - start < 60_000) {
            val responding = viewModel!!.isResponding.value
            if (responding) sawResponding = true
            if (!responding && (sawResponding || System.currentTimeMillis() - start > 5_000)) break
            Thread.sleep(200)
        }
        assertTrue("isResponding should be false at the end", !viewModel!!.isResponding.value)

        // Back to the list: the new chat's row must be visible
        device.pressBack()
        val row = device.wait(Until.hasObject(By.textContains("EMPTY_ACCOUNT_TEST_")), 10_000)
        assertNotNull("Chat row with the test message should be visible after back", row)
    }

    private fun waitForViewModel(timeout: Long = 10_000L): ChatViewModel? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeout) {
            navigationScopedViewModel?.let { return it }
            Thread.sleep(100)
        }
        return null
    }
}
