package com.example.whiz.integration

import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.di.AppModule
import com.example.whiz.services.AudioPipeRecorder
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test to verify that Acoustic Echo Cancellation (AEC) is available
 * and correctly attached when AudioPipeRecorder is created.
 *
 * This test creates a real AudioPipeRecorder (which opens the microphone) and
 * checks that AcousticEchoCanceler is attached and enabled throughout the
 * recording lifecycle.
 */
@LargeTest
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AECIntegrationTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "AECIntegrationTest"
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        // Grant microphone permission — required for AudioPipeRecorder
        Log.d(TAG, "Granting microphone permission for AEC test")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
    }

    @Test
    fun audioPipeRecorder_aecEnabledThroughLifecycle() {
        Log.d(TAG, "Verifying AEC is available on device")
        Assume.assumeTrue(
            "AcousticEchoCanceler not available on this device — skipping test",
            AcousticEchoCanceler.isAvailable()
        )

        Log.d(TAG, "Creating AudioPipeRecorder and verifying AEC attachment")
        val recorder = AudioPipeRecorder()
        try {
            assertTrue(
                "AEC should be enabled on AudioPipeRecorder after creation",
                recorder.isAECEnabled
            )

            recorder.start()
            assertTrue(
                "AEC should remain enabled while AudioPipeRecorder is recording",
                recorder.isAECEnabled
            )
            Log.d(TAG, "AEC confirmed enabled during recording")

            recorder.stop()
            assertFalse(
                "AEC should be released after AudioPipeRecorder.stop()",
                recorder.isAECEnabled
            )
            Log.d(TAG, "AEC correctly released after stop")
        } finally {
            recorder.cleanup()
        }
    }
}
