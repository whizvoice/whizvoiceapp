package com.example.whiz.voice

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.whiz.services.SpeechRecognitionService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.delay
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import io.mockk.slot
import android.speech.RecognitionListener
import android.os.Bundle

/**
 * Integration test that uses the real SpeechRecognitionService but mocks
 * the Android SpeechRecognizer APIs to test the actual bug fix code paths
 * without hanging on Android system services.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechCallbackPreservationTest {

    private lateinit var context: Context
    private lateinit var speechService: SpeechRecognitionService
    private lateinit var mockSpeechRecognizer: SpeechRecognizer

    @Before
    fun setup() {
        Log.d("TEST_MOCK", "🚀 SETUP STARTING...")
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Log.d("TEST_MOCK", "✓ Context obtained")
        
        // Mock static SpeechRecognizer methods
        mockkStatic(SpeechRecognizer::class)
        mockSpeechRecognizer = mockk(relaxed = true)
        
        // Mock the static methods
        every { SpeechRecognizer.isRecognitionAvailable(any()) } returns true
        every { SpeechRecognizer.createSpeechRecognizer(any()) } returns mockSpeechRecognizer
        
        speechService = SpeechRecognitionService(context)
        Log.d("TEST_MOCK", "✓ SpeechRecognitionService created with mocked dependencies")
        
        speechService.initialize()
        Log.d("TEST_MOCK", "✓ speechService.initialize() completed")
        Log.d("TEST_MOCK", "✅ SETUP COMPLETED")
    }

    @Test
    fun speechRecognition_realService_manualStopPreservesCallback() = runTest {
        Log.d("TEST_MOCK", "🏁 TEST: manualStopPreservesCallback")
        
        // This test uses the REAL SpeechRecognitionService with mocked Android APIs
        // to verify the actual bug fix in the production code
        
        assert(speechService.isInitialized) {
            "Service should be initialized with mocked dependencies"
        }
        Log.d("TEST_MOCK", "✓ Service initialized successfully")
        
        var callbackInvoked = false
        var capturedText: String? = null
        val listenerSlot = slot<RecognitionListener>()
        
        // Start listening - this should work with our mocked SpeechRecognizer
        speechService.startListening { finalText ->
            callbackInvoked = true
            capturedText = finalText
            Log.d("TEST_MOCK", "✓ Real callback invoked with: '$finalText'")
        }
        
        // Verify the real service called the Android API
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        verify { mockSpeechRecognizer.startListening(any()) }
        Log.d("TEST_MOCK", "✓ Real service properly called Android APIs")
        
        delay(100)
        assert(speechService.isListening.first()) {
            "Should be listening after startListening"
        }
        Log.d("TEST_MOCK", "✓ Service is in listening state")
        
        // THIS IS THE CRITICAL TEST: Manual stop should preserve callback
        // Before the bug fix: this would clear recognitionCallback = null
        // After the bug fix: callback should be preserved for final results
        speechService.stopListening()
        
        verify { mockSpeechRecognizer.stopListening() }
        Log.d("TEST_MOCK", "✓ Real service called stopListening on Android API")
        
        delay(50)
        assert(!speechService.isListening.first()) {
            "Should not be listening after stopListening"
        }
        
        // Now simulate what happens in the real world: Android delivers final results
        // via RecognitionListener.onResults AFTER stopListening was called
        val testResults = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("sorry it's really loud in here"))
        }
        
        Log.d("TEST_MOCK", "📝 Simulating Android delivering final results after manual stop...")
        listenerSlot.captured.onResults(testResults)
        
        delay(100) // Allow callback processing
        
        // VERIFY THE BUG FIX: Callback should have been preserved and invoked
        assert(callbackInvoked) {
            "BUG FIX VERIFICATION FAILED: Callback was not invoked after manual stop. " +
            "This means the final transcription was lost - the bug has returned!"
        }
        
        assert(capturedText == "sorry it's really loud in here") {
            "Final transcription should be delivered correctly. Expected: 'sorry it's really loud in here', Got: '$capturedText'"
        }
        
        Log.d("TEST_MOCK", "✅ CRITICAL BUG FIX VERIFIED with real service:")
        Log.d("TEST_MOCK", "   ✓ Manual stop preserved callback in real SpeechRecognitionService")
        Log.d("TEST_MOCK", "   ✓ Final results delivered correctly: '$capturedText'")
        Log.d("TEST_MOCK", "   ✓ Production code bug fix working!")
    }

    @Test
    fun speechRecognition_realService_continuousListeningScenario() = runTest {
        Log.d("TEST_MOCK", "🏁 TEST: continuousListeningScenario")
        
        // Test the exact user scenario that triggered the original bug
        assert(speechService.isInitialized)
        
        // Enable continuous listening (user starts voice typing)
        speechService.continuousListeningEnabled = true
        assert(speechService.continuousListeningEnabled)
        Log.d("TEST_MOCK", "✓ Continuous listening enabled")
        
        var finalTextReceived: String? = null
        val listenerSlot = slot<RecognitionListener>()
        
        speechService.startListening { text ->
            finalTextReceived = text
            Log.d("TEST_MOCK", "✓ Continuous listening callback: '$text'")
        }
        
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        delay(100)
        
        // User manually stops (disables continuous listening)
        // This is the exact sequence that caused the bug
        speechService.continuousListeningEnabled = false
        speechService.stopListening()
        
        assert(!speechService.continuousListeningEnabled)
        assert(!speechService.isListening.first())
        Log.d("TEST_MOCK", "✓ Manual stop completed, continuous listening disabled")
        
        // Final results arrive AFTER continuous listening was disabled
        // This is where the bug would lose the text
        val testResults = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("sorry it's really loud where i am"))
        }
        
        listenerSlot.captured.onResults(testResults)
        delay(100)
        
        // Verify text is preserved despite continuous listening being disabled
        assert(finalTextReceived == "sorry it's really loud where i am") {
            "Text should be preserved even when continuous listening disabled before final results. Got: '$finalTextReceived'"
        }
        
        Log.d("TEST_MOCK", "✅ CONTINUOUS LISTENING BUG SCENARIO PASSED with real service:")
        Log.d("TEST_MOCK", "   ✓ Text preserved when continuous listening disabled before final results")
        Log.d("TEST_MOCK", "   ✓ Real production code handles this scenario correctly")
    }

    @Test
    fun speechRecognition_realService_callbackCleanupAfterResults() = runTest {
        Log.d("TEST_MOCK", "🏁 TEST: callbackCleanupAfterResults")
        
        // Verify that callbacks are properly cleaned up after delivering results
        assert(speechService.isInitialized)
        
        var callbackCount = 0
        val listenerSlot = slot<RecognitionListener>()
        
        speechService.startListening { text ->
            callbackCount++
            Log.d("TEST_MOCK", "✓ Callback #$callbackCount: '$text'")
        }
        
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        delay(100)
        
        speechService.stopListening()
        delay(50)
        
        // Deliver first results
        val results1 = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("first result"))
        }
        listenerSlot.captured.onResults(results1)
        delay(50)
        
        assert(callbackCount == 1) {
            "Should have received first result"
        }
        
        // Try to deliver second results (shouldn't happen in practice, but test edge case)
        val results2 = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("second result"))
        }
        listenerSlot.captured.onResults(results2)
        delay(50)
        
        // Callback should be cleared after first delivery
        assert(callbackCount == 1) {
            "Callback should be cleared after first result delivery, not invoked again. Got $callbackCount callbacks"
        }
        
        Log.d("TEST_MOCK", "✅ CALLBACK CLEANUP VERIFIED with real service:")
        Log.d("TEST_MOCK", "   ✓ Callback cleared after delivering results")
        Log.d("TEST_MOCK", "   ✓ No duplicate callback invocations")
    }
} 