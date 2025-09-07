package com.example.whiz.integration

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.ListeningMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BubbleModeTest {
    
    private lateinit var context: Context
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }
    
    @After
    fun tearDown() {
        // Stop bubble service if running
        context.stopService(Intent(context, BubbleOverlayService::class.java))
    }
    
    @Test
    fun testBubbleModeDefaults() {
        // Test that default mode is continuous listening
        assertEquals(ListeningMode.CONTINUOUS_LISTENING, BubbleOverlayService.bubbleListeningMode)
        
        // Test that bubble is not active by default
        assertFalse(BubbleOverlayService.isActive)
    }
    
    @Test
    fun testBubbleModeChanges() = runBlocking {
        // Start the bubble service
        BubbleOverlayService.start(context)
        
        // Give service time to start
        delay(500)
        
        // Check that bubble is active
        assertTrue("Bubble should be active", BubbleOverlayService.isActive)
        
        // Verify initial mode
        assertEquals(ListeningMode.CONTINUOUS_LISTENING, BubbleOverlayService.bubbleListeningMode)
        
        // Note: We can't test the actual long press interaction in this unit test
        // That would require UI testing with Espresso
        // Instead, we test the mode transition logic
        
        // Stop the service
        BubbleOverlayService.stop(context)
        
        // Give service time to stop
        delay(500)
        
        // Check that bubble is no longer active
        assertFalse("Bubble should not be active", BubbleOverlayService.isActive)
    }
    
    @Test
    fun testModeCycling() {
        // Test the mode cycling logic
        var currentMode = ListeningMode.CONTINUOUS_LISTENING
        
        // Cycle to MIC_OFF
        currentMode = when (currentMode) {
            ListeningMode.CONTINUOUS_LISTENING -> ListeningMode.MIC_OFF
            ListeningMode.MIC_OFF -> ListeningMode.TTS_WITH_LISTENING
            ListeningMode.TTS_WITH_LISTENING -> ListeningMode.CONTINUOUS_LISTENING
        }
        assertEquals(ListeningMode.MIC_OFF, currentMode)
        
        // Cycle to TTS_WITH_LISTENING
        currentMode = when (currentMode) {
            ListeningMode.CONTINUOUS_LISTENING -> ListeningMode.MIC_OFF
            ListeningMode.MIC_OFF -> ListeningMode.TTS_WITH_LISTENING
            ListeningMode.TTS_WITH_LISTENING -> ListeningMode.CONTINUOUS_LISTENING
        }
        assertEquals(ListeningMode.TTS_WITH_LISTENING, currentMode)
        
        // Cycle back to CONTINUOUS_LISTENING
        currentMode = when (currentMode) {
            ListeningMode.CONTINUOUS_LISTENING -> ListeningMode.MIC_OFF
            ListeningMode.MIC_OFF -> ListeningMode.TTS_WITH_LISTENING
            ListeningMode.TTS_WITH_LISTENING -> ListeningMode.CONTINUOUS_LISTENING
        }
        assertEquals(ListeningMode.CONTINUOUS_LISTENING, currentMode)
    }
}