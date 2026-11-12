package com.example.whiz.viewmodels

import androidx.lifecycle.SavedStateHandle
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.TTSManager
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.viewmodels.VoiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Regression test for bug report #1516: TTS spoke a streamed response while the
 * screen was off. An utterance handed to the TTS engine reports isSpeaking=false
 * until the engine's onStart callback fires (~700ms later), so the backgrounding
 * teardown must stop TTS unconditionally — gating on isSpeaking misses utterances
 * that are queued but not yet started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTtsTeardownTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // Paused dispatcher: ChatViewModel's init-block collectors are queued, never run,
        // so unstubbed repository/service mocks are never touched.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `backgrounding without bubble stops TTS even when utterance has not started yet`() {
        val ttsManager = mock<TTSManager>()
        // Utterance submitted via speak() but engine onStart hasn't fired yet
        whenever(ttsManager.isSpeaking).thenReturn(MutableStateFlow(false))

        val voiceManager = mock<VoiceManager>()
        whenever(voiceManager.isVoiceResponseEnabled).thenReturn(MutableStateFlow(true))

        val viewModel = ChatViewModel(
            context = mock(),
            repository = mock<WhizRepository>(),
            whizServerRepository = mock(),
            authRepository = mock(),
            userPreferences = mock(),
            connectionStateManager = mock(),
            savedStateHandle = SavedStateHandle(),
            ttsManager = ttsManager,
            appLifecycleService = mock(),
            voiceManager = voiceManager,
            toolExecutor = mock(),
        )

        viewModel.onAppBackgrounded()

        verify(ttsManager).stop()
    }
}
