package com.example.whiz.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class MicrophonePermissionIntegrationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var device: UiDevice
    private lateinit var context: Context

    @Before
    fun setup() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Ensure microphone permission is revoked before test
        revokeMicrophonePermission()
    }

    // Test composable that simulates the permission dialog behavior
    @Composable
    private fun TestPermissionDialog(
        hasPermission: Boolean,
        onRequestPermission: () -> Unit
    ) {
        var showPermissionDialog by remember { mutableStateOf(false) }

        // Auto-prompt for microphone permission when app opens (if not already granted)
        LaunchedEffect(hasPermission) {
            if (!hasPermission) {
                // Small delay to ensure UI is fully composed before showing dialog
                delay(500)
                showPermissionDialog = true
            }
        }
        
        // Main content - simple placeholder
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Chat Interface Placeholder")
        }

        // Microphone permission dialog
        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text("Microphone Permission Required") },
                text = { Text("Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?") },
                confirmButton = {
                    Button(onClick = {
                        showPermissionDialog = false
                        onRequestPermission()
                    }) {
                        Text("Grant Permission")
                    }
                },
                dismissButton = {
                    Button(onClick = { showPermissionDialog = false }) {
                        Text("Not Now")
                    }
                }
            )
        }
    }

    @Test
    fun permissionDialog_withoutPermission_showsAutomaticallyAfterDelay() {
        var permissionRequested = false

        composeTestRule.setContent {
            TestPermissionDialog(
                hasPermission = false, // Force no permission
                onRequestPermission = { permissionRequested = true }
            )
        }

        // Initially, dialog should not be visible
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()

        // Wait for the delay (500ms) + buffer
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            try {
                composeTestRule
                    .onNodeWithText("Microphone Permission Required")
                    .assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Verify dialog content
        composeTestRule
            .onNodeWithText("Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?")
            .assertExists()

        // Test Grant Permission button
        composeTestRule
            .onNodeWithText("Grant Permission")
            .assertExists()
            .assertIsEnabled()
            .performClick()

        // Verify callback was called
        assert(permissionRequested) { "Permission request callback should have been called" }

        // Dialog should disappear
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()
    }

    @Test
    fun permissionDialog_withoutPermission_notNowButtonWorks() {
        composeTestRule.setContent {
            TestPermissionDialog(
                hasPermission = false, // Force no permission
                onRequestPermission = { }
            )
        }

        // Wait for dialog to appear
        composeTestRule.waitUntil(timeoutMillis = 2000) {
            try {
                composeTestRule
                    .onNodeWithText("Microphone Permission Required")
                    .assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        // Click "Not Now"
        composeTestRule
            .onNodeWithText("Not Now")
            .assertExists()
            .performClick()

        // Dialog should disappear
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()

        // Main content should still be visible
        composeTestRule
            .onNodeWithText("Chat Interface Placeholder")
            .assertExists()
    }

    @Test
    fun permissionDialog_withPermission_doesNotShow() {
        composeTestRule.setContent {
            TestPermissionDialog(
                hasPermission = true, // Force permission granted
                onRequestPermission = { }
            )
        }

        // Wait and ensure dialog never appears
        composeTestRule.waitForIdle()
        Thread.sleep(1000) // Wait longer than the delay

        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()

        // Main content should be visible
        composeTestRule
            .onNodeWithText("Chat Interface Placeholder")
            .assertExists()
    }

    @Test
    fun permissionDialog_realPermissionCheck_behaviorMatchesActualState() {
        var realPermissionGranted = false
        var permissionRequested = false

        composeTestRule.setContent {
            // Check actual permission status
            realPermissionGranted = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            
            TestPermissionDialog(
                hasPermission = realPermissionGranted,
                onRequestPermission = { permissionRequested = true }
            )
        }

        composeTestRule.waitForIdle()

        if (!realPermissionGranted) {
            // If permission is denied, dialog should appear
            composeTestRule.waitUntil(timeoutMillis = 2000) {
                try {
                    composeTestRule
                        .onNodeWithText("Microphone Permission Required")
                        .assertExists()
                    true
                } catch (e: AssertionError) {
                    false
                }
            }

            // Test that the dialog works correctly
            composeTestRule
                .onNodeWithText("Grant Permission")
                .performClick()

            assert(permissionRequested) { "Permission should have been requested when permission is denied" }
        } else {
            // If permission is granted, no dialog should appear
            Thread.sleep(1000)
            composeTestRule
                .onNodeWithText("Microphone Permission Required")
                .assertDoesNotExist()
        }

        // Main content should always be visible
        composeTestRule
            .onNodeWithText("Chat Interface Placeholder")
            .assertExists()
    }

    private fun revokeMicrophonePermission() {
        try {
            // Use shell command to revoke microphone permission
            device.executeShellCommand("pm revoke ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            Thread.sleep(300) // Give the system time to process
        } catch (e: Exception) {
            // If we can't revoke permission programmatically, log it
            println("Note: Could not revoke microphone permission programmatically: ${e.message}")
            println("Test will still verify behavior based on current permission state")
        }
    }
} 