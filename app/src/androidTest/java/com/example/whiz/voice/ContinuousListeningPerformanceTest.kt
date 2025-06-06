package com.example.whiz.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.services.SpeechRecognitionService
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.MockKAnnotations
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * Tests for continuous listening performance and timing to catch issues that could
 * degrade user experience like rate limiting blocking normal operations.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ContinuousListeningPerformanceTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @MockK
    private lateinit var mockSpeechRecognizer: SpeechRecognizer

    @Inject
    lateinit var speechService: SpeechRecognitionService

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        hiltRule.inject()

        // Configure mock to simulate successful creation
        every { SpeechRecognizer.createSpeechRecognizer(any()) } returns mockSpeechRecognizer
        every { SpeechRecognizer.isRecognitionAvailable(any()) } returns true
        every { mockSpeechRecognizer.setRecognitionListener(any()) } returns Unit
        every { mockSpeechRecognizer.startListening(any()) } returns Unit
        every { mockSpeechRecognizer.stopListening() } returns Unit
        every { mockSpeechRecognizer.cancel() } returns Unit
        every { mockSpeechRecognizer.destroy() } returns Unit

        clearMocks(mockSpeechRecognizer)
        speechService.initialize()
    }

    @After
    fun cleanup() {
        speechService.release()
    }

    @Test
    fun continuousListening_restartAfterEndOfSpeech_hasNoArtificialDelay() = runTest {
        // This test catches the rate limiting bug we just fixed
        speechService.continuousListeningEnabled = true
        val listenerSlot = slot<RecognitionListener>()
        
        var transcriptionReceived: String? = null
        speechService.startListening { text ->
            transcriptionReceived = text
        }
        
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        
        // Simulate normal end of speech
        val startTime = System.currentTimeMillis()
        listenerSlot.captured.onEndOfSpeech()
        
        // Verify restart happened immediately (no artificial delay)
        val restartTime = System.currentTimeMillis()
        val timeDifference = restartTime - startTime
        
        verify(exactly = 2) { mockSpeechRecognizer.startListening(any()) } // Initial + restart
        assert(timeDifference < 50) {
            "End of speech restart should be immediate, but took ${timeDifference}ms"
        }
    }

    @Test
    fun continuousListening_restartAfterResults_hasNoArtificialDelay() = runTest {
        // This test catches issues where normal conversation flow is blocked by rate limiting
        speechService.continuousListeningEnabled = true
        val listenerSlot = slot<RecognitionListener>()
        
        var transcriptionReceived: String? = null
        speechService.startListening { text ->
            transcriptionReceived = text
        }
        
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        
        // Simulate normal conversation: user speaks, gets results, wants to speak again
        val testResults = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("hello"))
        }
        
        val startTime = System.currentTimeMillis()
        listenerSlot.captured.onResults(testResults)
        
        // User immediately starts new speech session (like continuous listening should work)
        speechService.startListening { text ->
            // New callback for new session
        }
        
        val restartTime = System.currentTimeMillis()
        val timeDifference = restartTime - startTime
        
        // Verify no artificial delay prevented the restart
        verify(exactly = 2) { mockSpeechRecognizer.startListening(any()) } // Initial + restart
        assert(timeDifference < 50) {
            "Post-results restart should be immediate, but took ${timeDifference}ms"
        }
        assert(transcriptionReceived == "hello")
    }

    @Test
    fun continuousListening_rapidNormalRestarts_areNotRateLimited() = runTest {
        // Test that normal operational restarts work even when happening quickly
        speechService.continuousListeningEnabled = true
        val listenerSlot = slot<RecognitionListener>()
        
        speechService.startListening { }
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        
        // Simulate rapid but normal conversation pattern
        for (i in 1..5) {
            // End of speech -> restart cycle
            listenerSlot.captured.onEndOfSpeech()
            delay(50) // Short but realistic delay between speech segments
            
            // Deliver results
            val results = Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("word $i"))
            }
            listenerSlot.captured.onResults(results)
            
            if (i < 5) {
                // Start next session
                speechService.startListening { }
            }
        }
        
        // Verify all restarts were allowed (1 initial + 5 restarts)
        verify(exactly = 6) { mockSpeechRecognizer.startListening(any()) }
    }

    @Test
    fun continuousListening_rapidErrorRestarts_areRateLimited() = runTest {
        // Verify that error-based rate limiting still works
        speechService.continuousListeningEnabled = true
        val listenerSlot = slot<RecognitionListener>()
        
        speechService.startListening { }
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        
        // Simulate rapid error scenario (what rate limiting should prevent)
        for (i in 1..5) {
            listenerSlot.captured.onError(SpeechRecognizer.ERROR_NO_MATCH)
            delay(10) // Very rapid errors
        }
        
        // Should not have allowed all error restarts (initial + limited restarts)
        // Based on our MAX_ERROR_RESTARTS = 3
        verify(atMost = 4) { mockSpeechRecognizer.startListening(any()) }
    }

    @Test
    fun continuousListening_conversationFlowPerformance_meetsExpectations() = runTest {
        // End-to-end performance test for typical conversation flow
        speechService.continuousListeningEnabled = true
        val listenerSlot = slot<RecognitionListener>()
        
        val conversationTimes = mutableListOf<Long>()
        
        for (conversationTurn in 1..3) {
            val turnStartTime = System.currentTimeMillis()
            
            speechService.startListening { }
            verify(atLeast = conversationTurn) { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
            
            // Simulate complete conversation turn
            listenerSlot.captured.onBeginningOfSpeech()
            delay(100) // Speaking time
            listenerSlot.captured.onEndOfSpeech()
            
            val results = Bundle().apply {
                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("message $conversationTurn"))
            }
            listenerSlot.captured.onResults(results)
            
            val turnEndTime = System.currentTimeMillis()
            conversationTimes.add(turnEndTime - turnStartTime)
            
            delay(100) // Simulate brief pause between turns
        }
        
        // Verify conversation turns completed within reasonable time
        conversationTimes.forEach { turnTime ->
            assert(turnTime < 500) {
                "Conversation turn took ${turnTime}ms - too slow for good UX"
            }
        }
        
        // Verify all turns were processed
        verify(exactly = 3) { mockSpeechRecognizer.startListening(any()) }
    }

    @Test
    fun continuousListening_stateTransitions_areReactive() = runTest {
        // Test that state changes are reflected immediately
        speechService.continuousListeningEnabled = true
        
        var callbackCount = 0
        speechService.startListening { 
            callbackCount++
        }
        
        // Verify listening state changes immediately
        assert(speechService.isListening.first()) {
            "Should be listening immediately after startListening"
        }
        
        speechService.stopListening()
        
        withTimeout(100) {
            assert(!speechService.isListening.first()) {
                "Should stop listening immediately after stopListening"
            }
        }
    }

    @Test
    fun continuousListening_errorRecovery_maintainsPerformance() = runTest {
        // Test that error recovery doesn't degrade subsequent performance
        speechService.continuousListeningEnabled = true
        val listenerSlot = slot<RecognitionListener>()
        
        speechService.startListening { }
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        
        // Cause a recoverable error
        listenerSlot.captured.onError(SpeechRecognizer.ERROR_NO_MATCH)
        delay(100)
        
        // Now test normal operation performance after error
        val startTime = System.currentTimeMillis()
        speechService.startListening { }
        val restartTime = System.currentTimeMillis()
        
        assert(restartTime - startTime < 50) {
            "Post-error restart should be fast, but took ${restartTime - startTime}ms"
        }
    }

    @Test
    fun continuousListening_concurrentOperations_handleGracefully() = runTest {
        // Test edge cases where multiple operations happen simultaneously
        speechService.continuousListeningEnabled = true
        val listenerSlot = slot<RecognitionListener>()
        
        speechService.startListening { }
        verify { mockSpeechRecognizer.setRecognitionListener(capture(listenerSlot)) }
        
        // Simulate concurrent events (can happen in real usage)
        listenerSlot.captured.onBeginningOfSpeech()
        speechService.startListening { } // Called again while already listening
        listenerSlot.captured.onEndOfSpeech()
        speechService.startListening { } // Called again during restart
        
        // Should handle gracefully without crashes or blocking
        delay(100)
        verify(atLeast = 1) { mockSpeechRecognizer.startListening(any()) }
        assert(speechService.isInitialized)
    }
} 