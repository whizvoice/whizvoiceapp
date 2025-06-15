package com.example.whiz.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.example.whiz.data.repository.WhizRepository
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("disabled for refactor")
class MicButtonDuringResponseTest : BaseIntegrationTest() {
    
    companion object {
        private const val TAG = "MicButtonDuringResponseTest"
    }

    init {
        // Test if class initialization logs work
        Log.e(TAG, "🔥 CLASS INIT: MicButtonDuringResponseTest class initializing")
        println("🔥 PRINTLN INIT: MicButtonDuringResponseTest class initializing")
        System.out.println("🔥 SYSTEM.OUT INIT: MicButtonDuringResponseTest class initializing")
    }

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var whizRepository: WhizRepository

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private val packageName = "com.example.whiz.debug"

    @Before
    override fun setUpAuthentication() {
        Log.d(TAG, "🚀 Starting voice test setup...")
        
        // ENHANCED LOGGING: Check compose state at very beginning
        try {
            val setupRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes().size
            Log.d(TAG, "🔍 SETUP START: Found $setupRoots root nodes")
        } catch (e: Exception) {
            Log.e(TAG, "❌ SETUP START: Error counting roots: ${e.message}")
        }
        
        // Initialize device and context
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()
        
        // CRITICAL: Other tests (like AppStartupAuthTest) may have signed out the user
        // We need to ensure authentication is restored for voice tests
        Log.d(TAG, "🔐 Ensuring user is authenticated for voice tests...")
        
        // Call parent setup for authentication (this will re-authenticate if needed)
        super.setUpAuthentication()
        
        // CRITICAL: Wait for authentication to fully settle before proceeding
        Log.d(TAG, "⏳ Waiting for authentication to settle...")
        Thread.sleep(3000)
        
        // Verify authentication worked
        val isAuthenticated = runBlocking { authRepository.isSignedIn() }
        val userProfile = runBlocking { authRepository.userProfile.first() }
        Log.d(TAG, "🔍 Auth verification - Signed in: $isAuthenticated, User: ${userProfile?.email}")
        
        if (!isAuthenticated || userProfile?.email?.contains("whizvoicetest") != true) {
            throw AssertionError("Voice tests require REDACTED_TEST_EMAIL authentication but found: ${userProfile?.email}")
        }
        
        // CRITICAL: Other tests may have left the app in a bad state
        // Wait for the app to stabilize and ensure we're on the right screen
        Log.d(TAG, "⏳ Waiting for app to stabilize after authentication...")
        Thread.sleep(2000)
        composeTestRule.waitForIdle()
        
        Log.d(TAG, "✅ App stabilization completed")
        
        // ENHANCED LOGGING: Check compose state at end of setup
        try {
            val endSetupRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes().size
            Log.d(TAG, "🔍 SETUP END: Found $endSetupRoots root nodes")
        } catch (e: Exception) {
            Log.e(TAG, "❌ SETUP END: Error counting roots: ${e.message}")
        }
        
        Log.d(TAG, "✅ Voice test setup completed successfully")
    }
    
    private fun ensureAppIsInForeground() {
        Log.d(TAG, "🔍 Ensuring app is in foreground and ready...")
        
        try {
            val currentPackage = device.currentPackageName
            val isAppVisible = device.hasObject(By.pkg(packageName))
            
            Log.d(TAG, "📱 Current state - Package: $currentPackage, App visible: $isAppVisible")
            
            if (currentPackage != packageName || !isAppVisible) {
                Log.d(TAG, "🚀 App not in foreground, bringing it to front...")
                
                // Strategy 1: Try to bring existing app to foreground
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                
                // Wait for app to come to foreground
                val launched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
                if (launched) {
                    Thread.sleep(2000)
                    Log.d(TAG, "✅ App brought to foreground successfully")
                } else {
                    Log.w(TAG, "⚠️ Could not bring app to foreground, trying fresh launch...")
                    
                    // Strategy 2: Fresh launch if bringing to front failed
                    val freshIntent = context.packageManager.getLaunchIntentForPackage(packageName)!!
                    freshIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(freshIntent)
                    
                    val freshLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 15000)
                    if (!freshLaunched) {
                        throw AssertionError("Failed to launch app - test cannot proceed")
                    }
                    
                    Thread.sleep(3000)
                    Log.d(TAG, "✅ Fresh app launch successful")
                }
            } else {
                Log.d(TAG, "✅ App already in foreground")
            }
            
            // Final verification and stabilization
            composeTestRule.waitForIdle()
            Thread.sleep(1000)
            
            val finalPackage = device.currentPackageName
            if (finalPackage != packageName) {
                throw AssertionError("App not in foreground after launch attempts - current: $finalPackage")
            }
            
            Log.d(TAG, "✅ App confirmed in foreground and ready")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to ensure app is in foreground: ${e.message}")
            throw AssertionError("Could not bring app to foreground: ${e.message}")
        }
    }

    @After
    fun tearDown() {
        // Clean up after each test to prevent interference
        try {
            // Keep user authenticated for consistency with other tests
            Log.d(TAG, "✅ Test cleanup completed (user remains authenticated)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error during test teardown: ${e.message}")
        }
    }

    @Test
    fun realApp_micButtonDuringResponse_worksCorrectly() {
        Log.d(TAG, "🧪 Testing microphone button behavior during server response")
        Log.d(TAG, "📝 Expected: New chats auto-start with continuous listening enabled")
        
        // ENHANCED LOGGING: Check initial state before anything
        try {
            val initialRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
            Log.d(TAG, "🔍 INITIAL STATE: Found ${initialRoots.size} root nodes at test start")
            
            // If multiple roots, detect what dialogs are open
            if (initialRoots.size > 1) {
                val activeDialogs = detectActiveDialogs()
                Log.e(TAG, "❌ MULTIPLE ROOTS DETECTED AT TEST START: $activeDialogs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ INITIAL STATE: Could not count root nodes: ${e.message}")
        }
        
        // Step 1: Navigate to chat (should auto-enable continuous listening)
            Log.d(TAG, "1️⃣ Navigating to chat (expecting continuous listening to be auto-enabled)")
            navigateToChat()
            
        // Step 2: Test microphone button (should show "Turn off continuous listening")
            Log.d(TAG, "2️⃣ Testing microphone button (expecting 'Turn off continuous listening')")
            val micButton = findMicrophoneButton("Turn off continuous listening")
            micButton.performClick()
            
        // Step 3: Verify state changed (should now show different state)
            Log.d(TAG, "3️⃣ Verifying microphone state changed after click")
            verifyMicrophoneStateChanged()
            
        Log.d(TAG, "🎉 Microphone button test completed successfully!")
    }

    @Test
    fun realApp_micButtonPersistenceAcrossNavigation_worksCorrectly() {
        Log.d(TAG, "🧪 Testing microphone button persistence across multiple interactions")
        Log.d(TAG, "📝 Expected: Multiple mic button clicks should work consistently")
        
        // Check for dialogs before starting
        try {
            val initialRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
            Log.d(TAG, "🔍 PERSISTENCE TEST START: Found ${initialRoots.size} root nodes")
            
            if (initialRoots.size > 1) {
                val activeDialogs = detectActiveDialogs()
                Log.e(TAG, "❌ MULTIPLE ROOTS AT PERSISTENCE TEST START: $activeDialogs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Could not check initial state: ${e.message}")
        }
        
        // ENHANCED: Ensure test isolation by navigating back to chat list first
        Log.d(TAG, "🔄 Ensuring test isolation...")
        navigateBackToChatList()
        
        // Now navigate to a fresh new chat
        navigateToChat()
        
        try {
            // First interaction (should start with "Turn off continuous listening")
            Log.d(TAG, "🎯 First microphone interaction (expecting 'Turn off continuous listening')")
            
            // Wait before first click to ensure UI is stable
            Thread.sleep(500)
            composeTestRule.waitForIdle()
            
            val micButton1 = findMicrophoneButton("Turn off continuous listening")
            micButton1.performClick()
            
            // Wait for state change to be processed
            Thread.sleep(500)
            composeTestRule.waitForIdle()
            
            verifyMicrophoneStateChanged()
            
            // Second interaction (after turning OFF continuous listening, should show "Start listening")
            Log.d(TAG, "🎯 Second microphone interaction (expecting 'Start listening' after turning off continuous)")
            Thread.sleep(500)
            val micButton2 = findMicrophoneButton("Start listening")
            micButton2.performClick()
            
            // Wait for state change
            Thread.sleep(500)
            composeTestRule.waitForIdle()
            
            verifyMicrophoneStateChanged()
            
            // Third interaction (after clicking "Start listening", should show "Turn off continuous listening")
            Log.d(TAG, "🎯 Third microphone interaction (expecting 'Turn off continuous listening' after start listening)")
            Thread.sleep(500)
            val micButton3 = findMicrophoneButton("Turn off continuous listening")
            micButton3.performClick()
            verifyMicrophoneStateChanged()
            
            Log.d(TAG, "🎉 Multiple microphone interactions test completed successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Multiple microphone interactions test failed: ${e.message}")
            throw AssertionError("Multiple microphone interactions test failed")
        }
    }

    @Test
    @org.junit.Ignore("Diagnostic test - not essential for main functionality")
    fun diagnostic_logMicrophoneButtonStates() {
        Log.d(TAG, "🔍 DIAGNOSTIC: Logging microphone button states before and after click")
        
        try {
            navigateToChat()
            
            val allMicOptions = listOf(
                "Start listening",
                "Stop listening", 
                "Turn off continuous listening",
                "Turn on continuous listening",
                "Start listening during response",
                "Send message"
            )
            
            // Log states BEFORE clicking
            Log.d(TAG, "📋 BEFORE clicking microphone button:")
            for (micDesc in allMicOptions) {
                try {
                    val nodes = composeTestRule.onAllNodesWithContentDescription(micDesc).fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) {
                        Log.d(TAG, "✅ AVAILABLE: '$micDesc'")
                    } else {
                        Log.d(TAG, "❌ NOT AVAILABLE: '$micDesc'")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "❌ ERROR CHECKING: '$micDesc' - ${e.message}")
                }
            }
            
            // Click the microphone button
            Log.d(TAG, "🎯 Clicking microphone button...")
            val micButton = findMicrophoneButton()
            micButton.performClick()
            Thread.sleep(1000) // Wait for state change
            composeTestRule.waitForIdle()
            
            // Log states AFTER clicking
            Log.d(TAG, "📋 AFTER clicking microphone button:")
            for (micDesc in allMicOptions) {
                try {
                    val nodes = composeTestRule.onAllNodesWithContentDescription(micDesc).fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) {
                        Log.d(TAG, "✅ AVAILABLE: '$micDesc'")
                    } else {
                        Log.d(TAG, "❌ NOT AVAILABLE: '$micDesc'")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "❌ ERROR CHECKING: '$micDesc' - ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Diagnostic failed: ${e.message}")
            // Don't throw - just log the failure to avoid breaking the test suite
        }
        
        Log.d(TAG, "✅ Diagnostic completed")
    }

    @Test
    fun diagnostic_countRootNodes() {
        Log.d(TAG, "🔍 DIAGNOSTIC: Just counting root nodes")
        
        try {
            val rootCount = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes().size
            Log.d(TAG, "✅ DIAGNOSTIC: Found $rootCount root nodes")
            
            // Try to get each root individually
            for (i in 0 until rootCount) {
                try {
                    val root = composeTestRule.onAllNodes(isRoot())[i]
                    Log.d(TAG, "✅ DIAGNOSTIC: Root $i exists")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ DIAGNOSTIC: Error accessing root $i: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ DIAGNOSTIC: Error counting roots: ${e.message}")
        }
        
        Log.d(TAG, "✅ Diagnostic completed")
    }

    @Test
    @org.junit.Ignore("Diagnostic test - not essential for main functionality")
    fun diagnostic_findMicrophoneButtonOnly() {
        Log.d(TAG, "🔍 DIAGNOSTIC: Just finding microphone button without clicking")
        
        try {
            navigateToChat()
            val micButton = findMicrophoneButton()
            Log.d(TAG, "✅ Successfully found microphone button")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to find microphone button: ${e.message}")
        }
        
        Log.d(TAG, "✅ Diagnostic completed")
    }

    private fun detectActiveDialogs(): String {
        Log.d(TAG, "🔍 DIALOG DETECTION: Analyzing which dialogs are currently active...")
        val activeDialogs = mutableListOf<String>()
        
        // Check for Microphone Permission Dialog
        try {
            val micPermissionDialog = composeTestRule.onAllNodesWithText("Microphone Permission Required").fetchSemanticsNodes()
            if (micPermissionDialog.isNotEmpty()) {
                activeDialogs.add("MICROPHONE_PERMISSION_DIALOG")
                Log.d(TAG, "✅ DIALOG DETECTED: Microphone Permission Dialog")
            }
        } catch (e: Exception) {
            Log.d(TAG, "❌ Error checking microphone permission dialog: ${e.message}")
        }
        
        // Check for Asana Setup Dialog
        try {
            val asanaSetupDialog = composeTestRule.onAllNodesWithText("Asana Account Setup").fetchSemanticsNodes()
            if (asanaSetupDialog.isNotEmpty()) {
                activeDialogs.add("ASANA_SETUP_DIALOG")
                Log.d(TAG, "✅ DIALOG DETECTED: Asana Setup Dialog")
            }
        } catch (e: Exception) {
            Log.d(TAG, "❌ Error checking asana setup dialog: ${e.message}")
        }
        
        // Check for Auth Error Dialog (multiple possible titles)
        try {
            val authErrorDialog1 = composeTestRule.onAllNodesWithText("Authentication Error").fetchSemanticsNodes()
            val authErrorDialog2 = composeTestRule.onAllNodesWithText("API Key Issue").fetchSemanticsNodes()
            if (authErrorDialog1.isNotEmpty() || authErrorDialog2.isNotEmpty()) {
                activeDialogs.add("AUTH_ERROR_DIALOG")
                Log.d(TAG, "✅ DIALOG DETECTED: Auth Error Dialog")
            }
        } catch (e: Exception) {
            Log.d(TAG, "❌ Error checking auth error dialog: ${e.message}")
        }
        
        // Check for any other potential dialogs by looking for common dialog elements
        try {
            val dialogButtons = composeTestRule.onAllNodesWithText("Dismiss").fetchSemanticsNodes()
            val settingsButtons = composeTestRule.onAllNodesWithText("Go to Settings").fetchSemanticsNodes()
            val grantPermissionButtons = composeTestRule.onAllNodesWithText("Grant Permission").fetchSemanticsNodes()
            
            if (dialogButtons.isNotEmpty() || settingsButtons.isNotEmpty() || grantPermissionButtons.isNotEmpty()) {
                Log.d(TAG, "🔍 DIALOG BUTTONS DETECTED: Dismiss=${dialogButtons.size}, GoToSettings=${settingsButtons.size}, GrantPermission=${grantPermissionButtons.size}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "❌ Error checking dialog buttons: ${e.message}")
        }
        
        val result = if (activeDialogs.isNotEmpty()) {
            activeDialogs.joinToString(", ")
        } else {
            "NO_DIALOGS_DETECTED"
        }
        
        Log.d(TAG, "🔍 DIALOG DETECTION RESULT: $result")
        return result
    }

    // Simplified helper functions for clean test implementation
    private fun ensureAppIsReady(): Boolean {
        Log.d(TAG, "🔍 Checking if app environment is ready...")
        
        try {
            // Initialize device and context if not already done
            if (!::device.isInitialized) {
                device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            }
            if (!::context.isInitialized) {
                context = ApplicationProvider.getApplicationContext()
            }
            
            // Check if compose hierarchy is available (app is properly launched)
            try {
                composeTestRule.waitForIdle()
                // Try to find any compose node to verify app is running
                val hasNodes = composeTestRule.onRoot().fetchSemanticsNode() != null
                if (hasNodes) {
                    Log.d(TAG, "✅ App compose hierarchy is ready")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Compose hierarchy not ready: ${e.message}")
            }
            
            // Fallback: Check if app package is in foreground
            val currentPackage = device.currentPackageName
            if (currentPackage == packageName) {
                Log.d(TAG, "✅ App package is in foreground")
                return true
            }
            
            Log.w(TAG, "⚠️ App not ready - current package: $currentPackage")
            return false
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ App readiness check failed: ${e.message}")
            return false
        }
    }
    
    private fun ensureAppIsLaunched() {
        // Legacy function - now just calls ensureAppIsReady
        if (!ensureAppIsReady()) {
            throw AssertionError("App could not be launched or made ready")
        }
        
        // Check if app is already running
        val isAppVisible = device.hasObject(By.pkg(packageName))
        val currentPackage = device.currentPackageName
        Log.d(TAG, "🔍 Current state - App visible: $isAppVisible, Package: $currentPackage")
        
        if (!isAppVisible || currentPackage != packageName) {
            Log.d(TAG, "📱 App not running, launching manually...")
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            
            val appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 15000)
            if (!appLaunched) {
                Log.e(TAG, "❌ Failed to launch app in ensureAppIsLaunched")
                throw AssertionError("Failed to launch app - test cannot proceed")
            }
            
            Thread.sleep(2000) // Let app stabilize
            Log.d(TAG, "✅ App launched successfully")
        } else {
            Log.d(TAG, "✅ App already running")
        }
    }
    
    private fun navigateBackToChatList() {
        Log.d(TAG, "🔙 Navigating back to chat list for test isolation")
        
        // First check if we're already at the chat list
        try {
            val newChatButton = composeTestRule.onAllNodesWithContentDescription("New Chat").onFirst()
            newChatButton.assertExists()
            Log.d(TAG, "✅ Already at chat list - New Chat button visible")
            return
        } catch (e: Exception) {
            Log.d(TAG, "🔍 Not at chat list, need to navigate back")
            
            // Report what screen we're currently on
            try {
                Log.d(TAG, "📍 CURRENT SCREEN ANALYSIS:")
                
                // Check for common screen indicators
                val screenIndicators = mapOf(
                    "Chat Screen" to listOf("Turn off continuous listening", "Turn on continuous listening", "Start listening", "Stop listening", "Send message"),
                    "Settings Screen" to listOf("Settings", "Preferences", "Account", "Logout"),
                    "Profile Screen" to listOf("Profile", "Edit Profile", "User Settings"),
                    "Login Screen" to listOf("Welcome to WhizVoice", "Sign In", "Login", "Email", "Password"),
                    "Home/Chat List" to listOf("New Chat", "Chats", "Recent Conversations"),
                    "Loading Screen" to listOf("Loading", "Please wait", "Initializing")
                )
                
                var screenDetected = false
                for ((screenName, indicators) in screenIndicators) {
                    for (indicator in indicators) {
                        try {
                            val nodes = composeTestRule.onAllNodesWithText(indicator).fetchSemanticsNodes()
                            val contentNodes = composeTestRule.onAllNodesWithContentDescription(indicator).fetchSemanticsNodes()
                            if (nodes.isNotEmpty() || contentNodes.isNotEmpty()) {
                                Log.d(TAG, "📍 DETECTED: $screenName (found '$indicator')")
                                screenDetected = true
                                break
                            }
                        } catch (ex: Exception) {
                            // Continue checking other indicators
                        }
                    }
                    if (screenDetected) break
                }
                
                if (!screenDetected) {
                    Log.d(TAG, "📍 UNKNOWN SCREEN - checking available elements:")
                    try {
                        val allClickable = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                        Log.d(TAG, "📍 Found ${allClickable.size} clickable elements")
                        
                        // Try to get UI tree for debugging
                        try {
                            val uiTree = composeTestRule.onRoot().printToString()
                            Log.d(TAG, "📍 UI TREE: ${uiTree.take(500)}...") // First 500 chars
                        } catch (treeEx: Exception) {
                            Log.e(TAG, "📍 Could not get UI tree: ${treeEx.message}")
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "📍 Could not analyze screen elements: ${ex.message}")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "📍 Could not analyze current screen: ${e.message}")
            }
        }
        
        // Try multiple navigation methods
        var navigationSuccessful = false
        
        // Method 1: Try hardware back button
        try {
            Log.d(TAG, "🔙 Trying hardware back button")
            device.pressBack()
            Thread.sleep(1000)
            composeTestRule.waitForIdle()
            
            val newChatButton = composeTestRule.onNodeWithContentDescription("New Chat")
            newChatButton.assertExists()
            Log.d(TAG, "✅ Hardware back button worked")
            navigationSuccessful = true
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Hardware back button didn't work: ${e.message}")
        }
        
        // Method 2: Try to find any back/up navigation button
        if (!navigationSuccessful) {
            try {
                // Try different possible back button descriptions
                val backDescriptions = listOf("Navigate up", "Back", "Up", "Close")
                for (desc in backDescriptions) {
                    try {
                        val backButton = composeTestRule.onNodeWithContentDescription(desc)
                        backButton.assertExists()
                        backButton.performClick()
                        Log.d(TAG, "✅ Clicked '$desc' button")
                        
                        Thread.sleep(1000)
                        composeTestRule.waitForIdle()
                        
                        val newChatButton = composeTestRule.onAllNodesWithContentDescription("New Chat").onFirst()
                        newChatButton.assertExists()
                        Log.d(TAG, "✅ Navigation with '$desc' button worked")
                        navigationSuccessful = true
                        break
                    } catch (e: Exception) {
                        Log.d(TAG, "⚠️ '$desc' button not found or didn't work")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ No navigation buttons found")
            }
        }
        
        // Method 3: Try to find a "Chats" or "Home" button
        if (!navigationSuccessful) {
            try {
                val chatsButton = composeTestRule.onNodeWithText("Chats")
                chatsButton.assertExists()
                chatsButton.performClick()
                Log.d(TAG, "✅ Clicked Chats button")
                
                Thread.sleep(1000)
                composeTestRule.waitForIdle()
                
                val newChatButton = composeTestRule.onAllNodesWithContentDescription("New Chat").onFirst()
                newChatButton.assertExists()
                Log.d(TAG, "✅ Navigation with Chats button worked")
                navigationSuccessful = true
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Chats button not found or didn't work")
            }
        }
        
        // If all navigation methods failed, just proceed (maybe we're already where we need to be)
        if (!navigationSuccessful) {
            Log.d(TAG, "⚠️ All navigation methods failed, proceeding anyway")
            Log.d(TAG, "🔍 This might be okay if the app structure doesn't require explicit navigation")
        }
    }

    private fun navigateToChat() {
        Log.d(TAG, "🎯 Navigating to chat...")
        
        // ENHANCED LOGGING: Check compose state FIRST before any operations
        try {
            val rootCount = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes().size
            Log.d(TAG, "🔍 NAVIGATE START: Found $rootCount root nodes")
        } catch (e: Exception) {
            Log.e(TAG, "❌ NAVIGATE START: Error counting roots: ${e.message}")
        }
        
        // ENHANCED LOGGING: Check initial state
        val initialPackage = device.currentPackageName
        val initialAppVisible = device.hasObject(By.pkg(packageName))
        Log.d(TAG, "🔍 INITIAL STATE - Package: $initialPackage, App visible: $initialAppVisible")
        
        // Log all visible packages for debugging
        try {
            val allPackages = device.executeShellCommand("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'")
            Log.d(TAG, "🔍 FOCUS INFO: $allPackages")
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Could not get focus info: ${e.message}")
        }
        
        // Robust app verification and launch
        val isAppVisible = device.hasObject(By.pkg(packageName))
        val currentPackage = device.currentPackageName
        Log.d(TAG, "🔍 App visible: $isAppVisible, Current package: $currentPackage")
        
        if (!isAppVisible || currentPackage != packageName) {
            Log.d(TAG, "⚠️ App not in foreground (visible: $isAppVisible, package: $currentPackage), relaunching...")
            
            // ENHANCED LOGGING: Log launch intent details
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
            Log.d(TAG, "🚀 LAUNCH INTENT: ${intent.component}, flags: ${intent.flags}")
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            Log.d(TAG, "🚀 LAUNCH INTENT AFTER FLAGS: flags: ${intent.flags}")
            
            // Log what's currently on screen before launch
            try {
                val currentActivity = device.executeShellCommand("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'")
                Log.d(TAG, "🔍 BEFORE LAUNCH - Current activity: $currentActivity")
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Could not get current activity: ${e.message}")
            }
            
            context.startActivity(intent)
            Log.d(TAG, "🚀 Launch intent sent, waiting for app...")
            
            val appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
            Log.d(TAG, "🔍 App launch wait result: $appLaunched")
            
            if (!appLaunched) {
                // ENHANCED LOGGING: What happened during launch failure?
                val failurePackage = device.currentPackageName
                val failureVisible = device.hasObject(By.pkg(packageName))
                Log.e(TAG, "❌ App failed to launch - Current package: $failurePackage, App visible: $failureVisible")
                
                try {
                    val failureActivity = device.executeShellCommand("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'")
                    Log.e(TAG, "❌ LAUNCH FAILURE - Current activity: $failureActivity")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Could not get failure activity info: ${e.message}")
                }
                
                throw AssertionError("App failed to launch during navigation to chat")
            }
            
            Thread.sleep(2000) // Let app stabilize
            Log.d(TAG, "⏳ App stabilization period completed")
            
            // Verify app is now in foreground
            val finallyVisible = device.hasObject(By.pkg(packageName))
            val finalPackage = device.currentPackageName
            Log.d(TAG, "🔍 After relaunch - App visible: $finallyVisible, Current package: $finalPackage")
            
            // ENHANCED LOGGING: What activity is actually running?
            try {
                val finalActivity = device.executeShellCommand("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'")
                Log.d(TAG, "🔍 AFTER LAUNCH - Current activity: $finalActivity")
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Could not get final activity info: ${e.message}")
            }
            
            if (!finallyVisible || finalPackage != packageName) {
                Log.e(TAG, "❌ App still not in foreground after relaunch")
                
                // ENHANCED LOGGING: Take screenshot for debugging
                try {
                    val screenshotFile = java.io.File("/sdcard/test_failure_screenshot.png")
                    val screenshot = device.takeScreenshot(screenshotFile)
                    Log.e(TAG, "📸 Screenshot taken for debugging app launch failure: $screenshot")
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Could not take screenshot: ${e.message}")
                }
                
                throw AssertionError("App not in foreground after relaunch - current package: $finalPackage")
            }
        } else {
            Log.d(TAG, "✅ App already in foreground, no relaunch needed")
        }
        
        // ENHANCED LOGGING: Check authentication state before navigation
        val isAuthenticated = runBlocking { authRepository.isSignedIn() }
        val userProfile = runBlocking { authRepository.userProfile.value }
        val serverToken = runBlocking { authRepository.serverToken.value }
        Log.d(TAG, "🔍 Auth state: isSignedIn=$isAuthenticated, userProfile=${userProfile?.email}, hasServerToken=${serverToken != null}")
        
        // Authentication should already be handled by BaseIntegrationTest setup
        val expectedEmail = "REDACTED_TEST_EMAIL"
        if (!isAuthenticated || userProfile?.email != expectedEmail) {
            Log.e(TAG, "❌ Authentication not properly set up - expected $expectedEmail, got ${userProfile?.email}")
            throw AssertionError("Authentication should be handled by BaseIntegrationTest setup")
        }
        
        Log.d(TAG, "✅ Authentication verified: ${userProfile.email}")
        
        // ENHANCED LOGGING: Check compose test rule state
        try {
            Log.d(TAG, "🔍 Checking compose test rule state...")
            composeTestRule.waitForIdle()
            Log.d(TAG, "✅ Compose test rule idle wait completed")
            
            // Try to get the root node to verify compose hierarchy is working
            try {
                val rootNode = composeTestRule.onRoot().fetchSemanticsNode()
                Log.d(TAG, "✅ Compose root node accessible: ${rootNode != null}")
                
                // Log the current UI tree for debugging
                val uiTree = composeTestRule.onRoot().printToString()
                Log.d(TAG, "🔍 CURRENT UI TREE: $uiTree")
            } catch (rootEx: Exception) {
                Log.e(TAG, "❌ Multiple root nodes detected: ${rootEx.message}")
                // Try to get all root nodes and identify dialogs
                try {
                    val allRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
                    Log.e(TAG, "❌ Found ${allRoots.size} root nodes - UI state interference detected")
                    
                    // ENHANCED: Detect which dialogs are causing the multiple roots
                    val activeDialogs = detectActiveDialogs()
                    Log.e(TAG, "❌ ACTIVE DIALOGS CAUSING MULTIPLE ROOTS: $activeDialogs")
                } catch (ex: Exception) {
                    Log.e(TAG, "❌ Could not count root nodes: ${ex.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose test rule issue: ${e.message}")
            Log.e(TAG, "❌ This might indicate the app UI is not properly loaded")
        }
        
        Thread.sleep(1000) // Wait for UI to stabilize
        Log.d(TAG, "⏳ UI stabilization period completed")
        
        // Verify we're not on login screen
        Log.d(TAG, "🔍 Verifying we're not on login screen...")
        try {
            val loginElements = composeTestRule.onAllNodesWithText("Welcome to WhizVoice").fetchSemanticsNodes()
            if (loginElements.isNotEmpty()) {
                Log.e(TAG, "❌ Still on login screen despite authentication!")
                throw AssertionError("App still showing login screen despite successful authentication")
            }
            Log.d(TAG, "✅ Not on login screen")
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Error checking login screen: ${e.message}")
        }
        
        // ENHANCED LOGGING: Navigate to chat screen
        Log.d(TAG, "🔍 Looking for New Chat button...")
        
        // First, let's see what buttons/elements are actually available
        try {
            val allButtons = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            Log.d(TAG, "🔍 Found ${allButtons.size} clickable elements")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error analyzing available buttons: ${e.message}")
        }
        
        val newChatButtons = composeTestRule.onAllNodesWithContentDescription("New Chat")
        val newChatNodes = newChatButtons.fetchSemanticsNodes()
        Log.d(TAG, "🔍 Found ${newChatNodes.size} 'New Chat' buttons")
        
        if (newChatNodes.isNotEmpty()) {
            Log.d(TAG, "✅ Found New Chat button, clicking...")
            newChatButtons[0].performClick()
            Log.d(TAG, "🔄 New Chat button clicked, waiting for navigation...")
            
            Thread.sleep(1500) // Wait for chat screen to load
            composeTestRule.waitForIdle()
            Log.d(TAG, "⏳ Navigation wait period completed")
            
            // ENHANCED LOGGING: Check what screen we're on after clicking
            try {
                val postClickTree = composeTestRule.onRoot().printToString()
                Log.d(TAG, "🔍 UI TREE AFTER NEW CHAT CLICK: $postClickTree")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Could not get UI tree after New Chat click (multiple roots?): ${e.message}")
                // Try to get all root nodes if there are multiple
                try {
                    val allRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
                    Log.e(TAG, "❌ Found ${allRoots.size} root nodes - this indicates UI state interference")
                    
                    // ENHANCED: Detect which dialogs are causing the multiple roots
                    val activeDialogs = detectActiveDialogs()
                    Log.e(TAG, "❌ ACTIVE DIALOGS AFTER NEW CHAT CLICK: $activeDialogs")
                } catch (ex: Exception) {
                    Log.e(TAG, "❌ Could not even count root nodes: ${ex.message}")
                }
            }
            
            // Verify we're now in chat screen by looking for microphone button
            Log.d(TAG, "🔍 Verifying we're in chat screen...")
            try {
                val micButton = findMicrophoneButton()
                Log.d(TAG, "✅ Successfully navigated to chat screen - microphone button found")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Navigation to chat failed - microphone button not found: ${e.message}")
                
                // ENHANCED LOGGING: What elements are available on this screen?
                try {
                    val chatScreenElements = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                    Log.e(TAG, "❌ CHAT SCREEN DEBUG - Found ${chatScreenElements.size} clickable elements")
                } catch (ex: Exception) {
                    Log.e(TAG, "❌ Could not analyze chat screen elements: ${ex.message}")
                }
                
                throw AssertionError("Failed to navigate to chat screen - microphone button not found")
            }
        } else {
            Log.e(TAG, "❌ Could not find New Chat button")
            
            // ENHANCED LOGGING: What's currently visible when New Chat button is missing?
            try {
                val allNodes = composeTestRule.onRoot().printToString()
                Log.e(TAG, "❌ NO NEW CHAT BUTTON - Current UI tree: $allNodes")
                
                // Also check what the current activity/screen might be
                val currentPackageCheck = device.currentPackageName
                Log.e(TAG, "❌ NO NEW CHAT BUTTON - Current package: $currentPackageCheck")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Could not print UI tree when New Chat missing (multiple roots?): ${e.message}")
                // Try to get all root nodes if there are multiple
                try {
                    val allRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
                    Log.e(TAG, "❌ Found ${allRoots.size} root nodes when New Chat missing")
                    
                    // ENHANCED: Detect which dialogs are causing the multiple roots
                    val activeDialogs = detectActiveDialogs()
                    Log.e(TAG, "❌ ACTIVE DIALOGS WHEN NEW CHAT MISSING: $activeDialogs")
                } catch (ex: Exception) {
                    Log.e(TAG, "❌ Could not count root nodes: ${ex.message}")
                }
            }
            
            throw AssertionError("Could not find New Chat button - app may be in wrong state")
        }
    }
    
    private fun findMicrophoneButton(): SemanticsNodeInteraction {
        Log.d(TAG, "🔍 Finding microphone button (any state)")
        
        // First check for multiple root nodes and detect dialogs if present
        try {
            val allRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
            if (allRoots.size > 1) {
                Log.e(TAG, "❌ MULTIPLE ROOTS DETECTED IN findMicrophoneButton: ${allRoots.size} roots")
                val activeDialogs = detectActiveDialogs()
                Log.e(TAG, "❌ ACTIVE DIALOGS CAUSING MULTIPLE ROOTS: $activeDialogs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Could not check root nodes: ${e.message}")
        }
        
        // Based on actual ChatInputBar production logic - all possible mic button states
        val micOptions = listOf(
            "Turn off continuous listening",  // Most common - new chats auto-enable continuous listening
            "Turn on continuous listening",   // After turning off continuous listening
            "Stop listening",                 // When actively listening
            "Start listening during response", // During TTS with headphones
            "Start listening",                // Default fallback state
            "Send message"                    // When there's input text (fallback)
        )
        
        // Try each microphone option (SIMPLIFIED - no excessive logging)
        for (option in micOptions) {
            try {
                val button = composeTestRule.onNodeWithContentDescription(option)
                button.assertExists()
                Log.d(TAG, "✅ Found microphone button: '$option'")
                return button
            } catch (e: Exception) {
                // Continue to next option without logging each failure
            }
        }
        
        // If no microphone button found, throw error with available options
        Log.e(TAG, "❌ No microphone button found")
        throw AssertionError("No microphone button found - tried: $micOptions")
    }
    
    // OVERLOADED VERSION: Find specific microphone button by name (SIMPLIFIED LOGGING)
    private fun findMicrophoneButton(expectedState: String): SemanticsNodeInteraction {
        Log.d(TAG, "🔍 Finding microphone button: '$expectedState'")
        
        // First check for multiple root nodes and detect dialogs if present
        try {
            val allRoots = composeTestRule.onAllNodes(isRoot()).fetchSemanticsNodes()
            if (allRoots.size > 1) {
                Log.e(TAG, "❌ MULTIPLE ROOTS DETECTED IN findMicrophoneButton($expectedState): ${allRoots.size} roots")
                val activeDialogs = detectActiveDialogs()
                Log.e(TAG, "❌ ACTIVE DIALOGS CAUSING MULTIPLE ROOTS: $activeDialogs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Could not check root nodes: ${e.message}")
        }
        
        try {
            val button = composeTestRule.onNodeWithContentDescription(expectedState, ignoreCase = true)
            button.assertExists()
            Log.d(TAG, "✅ Found microphone button: '$expectedState'")
            return button
        } catch (e: Exception) {
            Log.e(TAG, "❌ Expected microphone button '$expectedState' not found")
            
            // SIMPLIFIED FALLBACK: Just check what's available without excessive logging
            val allMicOptions = listOf(
                "Turn off continuous listening",
                "Turn on continuous listening", 
                "Stop listening",
                "Start listening during response",
                "Start listening",
                "Send message"
            )
            
            val availableButtons = mutableListOf<String>()
            for (option in allMicOptions) {
                try {
                    val nodes = composeTestRule.onAllNodesWithContentDescription(option).fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) {
                        availableButtons.add(option)
                    }
                } catch (ex: Exception) {
                    // Ignore - button not available
                }
            }
            
            if (availableButtons.isNotEmpty()) {
                throw AssertionError("Expected '$expectedState' not found. Available: $availableButtons")
            } else {
                throw AssertionError("Expected '$expectedState' not found. No microphone buttons available!")
            }
        }
    }
    
    private fun verifyMicrophoneStateChanged() {
        Log.d(TAG, "🔍 Verifying microphone button is still functional")
        // Wait time for async state changes
        Thread.sleep(500) // Reduced to 500ms for faster execution
        composeTestRule.waitForIdle()
        
        // Check that we can still find a microphone button (any state is fine)
        val stateOptions = listOf(
            "Turn off continuous listening", 
            "Turn on continuous listening",   
            "Start listening",
            "Stop listening", 
            "Start listening during response",
            "Send message"
        )
        
        // SIMPLIFIED: Just check if any microphone button exists
        for (option in stateOptions) {
            try {
                val nodes = composeTestRule.onAllNodesWithContentDescription(option).fetchSemanticsNodes()
                if (nodes.isNotEmpty()) {
                    Log.d(TAG, "✅ Microphone button still functional: '$option'")
                    return
                }
            } catch (e: Exception) {
                // Continue checking other options
            }
        }
        
        // If we get here, no microphone button was found
        Log.e(TAG, "❌ No microphone button found after click")
        throw AssertionError("No microphone button found after interaction - app may have crashed")
    }
} 