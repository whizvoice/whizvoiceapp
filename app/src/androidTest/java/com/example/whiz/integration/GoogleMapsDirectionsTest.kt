package com.example.whiz.integration

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import com.example.whiz.ToolFlowIntegrationTest
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.data.local.MessageType
import com.example.whiz.di.AppModule
import com.example.whiz.test_helpers.SkipOnCIOrEmulatorRule
import com.example.whiz.tools.ToolExecutionResult
import com.example.whiz.ui.viewmodels.ChatViewModel
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * Screen agent Google Maps directions test.
 *
 * Search for a store, ask for directions to one of the results, switch the destination with
 * public transit, then switch to driving, and finally check the assistant's last chat message
 * names the second address. Mode is proven two ways: the tool result's own `mode` field and the
 * on-screen state in Google Maps, using the same resource-id signatures the app's
 * ScreenAgentTools.detectGoogleMapsScreenState uses (live turn-by-turn navigation vs the transit
 * route-detail screen).
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GoogleMapsDirectionsTest : ToolFlowIntegrationTest() {

    companion object {
        private const val TAG = "GoogleMapsDirectionsTest"
        private const val MAPS_PKG = "com.google.android.apps.maps"

        // Maps screen-state signatures (ScreenAgentTools.detectGoogleMapsScreenState, confirmed on a
        // live dump 2026-11-12):
        //  - ACTIVE_NAVIGATION:    nav_container present
        //  - TRANSIT_ROUTE_DETAIL: trip_details_footer_layout present and directions_mode_tabs absent
        private val NAV_CONTAINER: BySelector = By.pkg(MAPS_PKG).res("$MAPS_PKG:id/nav_container")
        private val TRIP_DETAILS_FOOTER: BySelector = By.pkg(MAPS_PKG).res("$MAPS_PKG:id/trip_details_footer_layout")
        private val MODE_TABS: BySelector = By.pkg(MAPS_PKG).res("$MAPS_PKG:id/directions_mode_tabs")
        private val ETA_TEXT: BySelector = By.pkg(MAPS_PKG).text(Pattern.compile(".*\\b\\d+ (min|hr)\\b.*"))

        private val SEARCH_TOOLS = setOf("agent_search_google_maps_phrase", "agent_search_google_maps_location")
        private val SELECT_TOOLS = setOf("agent_select_location_from_list", "agent_get_google_maps_directions")
        private const val DIRECTIONS_TOOL = "agent_get_google_maps_directions"

        // Directions include the Start tap plus the app's own navigation-started verification.
        private const val TOOL_TIMEOUT_MS = 90_000L
        private const val SCREEN_TIMEOUT_MS = 60_000L
    }

    private data class MapsCity(
        val storeName: String,
        val searchQuery: String,
        val locationSelector: String,
        val secondaryAddress: String,
        val secondaryAddressShort: String,
    )

    private val sanFrancisco = MapsCity(
        storeName = "Trader Joe's",
        searchQuery = "what are the trader joes near me ?",
        locationSelector = "Can you give me directions to the one on Laguna Street",
        secondaryAddress = "1680 Mission Street",
        secondaryAddressShort = "1680 Mission St",
    )
    private val toronto = MapsCity(
        storeName = "Shoppers Drug Mart",
        searchQuery = "what are the shoppers drug mart near me ?",
        locationSelector = "Can you give me directions to the closest one",
        secondaryAddress = "220 Yonge Street",
        secondaryAddressShort = "220 Yonge St",
    )

    /** Keyed on the phone's timezone (the Python test keys on the host's; they match when phone and Mac travel together). */
    private val city: MapsCity
        get() {
            val tz = TimeZone.getDefault()
            val eastern = tz.id.startsWith("America/") && tz.rawOffset == TimeZone.getTimeZone("America/Toronto").rawOffset
            return if (eastern) toronto else sanFrancisco
        }

    @get:Rule(order = 3)
    val skipOnCI = SkipOnCIOrEmulatorRule()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        startToolResultCollector()
        device.executeShellCommand("am force-stop $MAPS_PKG")
    }

    @After
    fun cleanup() {
        stopToolResultCollector()
        runBlocking {
            stopBubbleOverlayIfActive()
            cleanupTrackedChats()
        }
        try {
            device.executeShellCommand("am force-stop $MAPS_PKG")
        } catch (_: Exception) {
        }
    }

    private fun isActiveNavigation() = device.hasObject(NAV_CONTAINER) && device.hasObject(ETA_TEXT)
    private fun isTransitRouteDetail() = device.hasObject(TRIP_DETAILS_FOOTER) && !device.hasObject(MODE_TABS)
    private fun isRouteInProgress() = isActiveNavigation() || isTransitRouteDetail()

    /**
     * Proves the screen asserted afterwards belongs to the new request, not to the previous step's
     * route (step 2 may already have ended on transit route detail): the Maps screen signature
     * (mode tabs / trip-details footer / nav container) must change from what it was when the
     * request was sent. A new-destination re-route passes through a no-route search state; a
     * mode-only re-route (transit -> driving) can jump straight to live navigation, so any change
     * counts. Polled tightly: intermediate states can last under a second.
     */
    private suspend fun awaitRouteLeftThenReached(what: String, tag: String, fromIndex: Int, reached: () -> Boolean) {
        fun signature() = "tabs=${device.hasObject(MODE_TABS)} footer=${device.hasObject(TRIP_DETAILS_FOOTER)} nav=${device.hasObject(NAV_CONTAINER)}"
        val startSig = signature()
        val leftDeadline = System.currentTimeMillis() + TOOL_TIMEOUT_MS
        var left = false
        val trace = StringBuilder("[start] $startSig; ")
        while (System.currentTimeMillis() < leftDeadline) {
            val sig = signature()
            if (sig != startSig) {
                trace.append("[${System.currentTimeMillis() % 100_000}] $sig; ")
                left = true
                break
            }
            kotlinx.coroutines.delay(50)
        }
        Log.i(TAG, "route transition trace for $what: $trace")
        if (!left) {
            // Distinguish "the tool ran but Maps didn't move" from "nothing ran at all" (a server
            // error on the request leaves the screen untouched and no tool result behind).
            val toolRan = toolResults.drop(fromIndex).any {
                when (it) {
                    is ToolExecutionResult.Success -> it.toolName == DIRECTIONS_TOOL
                    is ToolExecutionResult.Error -> it.toolName == DIRECTIONS_TOOL
                    else -> false
                }
            }
            val why = if (toolRan) "the directions tool ran but Maps never changed" else "no directions tool ran (server error or no tool call?)"
            failWithScreenshot("Maps screen never changed from '$startSig' while re-routing for $what: $why", "${tag}_route_unchanged")
        }
        if (!waitUntil(SCREEN_TIMEOUT_MS) { reached() }) {
            failWithScreenshot("Maps did not reach $what", tag)
        }
    }

    private suspend fun lastAssistantMessage(chatViewModel: ChatViewModel): String =
        chatViewModel.messages.first()
            .filter { it.type == MessageType.ASSISTANT }
            .maxByOrNull { it.timestamp }
            ?.content
            ?: ""

    @Test
    fun testMapsDirections_search_select_transit_drive(): Unit = runBlocking {
        assertTrue(
            "Real accessibility service must be connected for the screen agent to drive Maps",
            WhizAccessibilityService.isServiceConnected()
        )
        val city = city
        Log.i(TAG, "city config: ${city.storeName} (tz=${TimeZone.getDefault().id})")
        voiceManager.setVoiceResponseEnabled(false)
        val chatViewModel = launchVoiceModeAndCaptureViewModel()

        // ---- Step 1: search; expect Maps in front with several results for the store ----
        val mark1 = toolResults.size
        sendVoice(chatViewModel, city.searchQuery)
        val search = awaitSuccess(SEARCH_TOOLS, mark1, TOOL_TIMEOUT_MS, "maps_search")
        Log.i(TAG, "search result: ${search.result}")
        assertTrue("Maps should be in the foreground", device.wait(Until.hasObject(By.pkg(MAPS_PKG).depth(0)), 10_000))
        val resultsSelector = By.pkg(MAPS_PKG).textContains(city.storeName)
        if (!waitUntil(SCREEN_TIMEOUT_MS) { device.findObjects(resultsSelector).size >= 2 }) {
            failWithScreenshot("Maps did not show at least two '${city.storeName}' results", "maps_results_list")
        }
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 1 ok")

        // ---- Step 2: pick one; expect a route in progress (navigation or transit detail) ----
        val mark2 = toolResults.size
        sendVoice(chatViewModel, city.locationSelector)
        val select = awaitSuccess(SELECT_TOOLS, mark2, TOOL_TIMEOUT_MS, "maps_select")
        Log.i(TAG, "select result: ${select.result}")
        if (!waitUntil(SCREEN_TIMEOUT_MS) { isRouteInProgress() }) {
            failWithScreenshot("Maps is not showing a route in progress after selecting the location", "maps_no_route")
        }
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 2 ok")

        // ---- Step 3: new destination by public transit; expect transit mode + transit route detail ----
        val mark3 = toolResults.size
        sendVoice(chatViewModel, "Actually, I need to go to ${city.secondaryAddress} first. Can you get public transit directions to there instead?")
        awaitRouteLeftThenReached("the transit route-detail screen", "maps_not_transit", mark3) { isTransitRouteDetail() }
        val transit = awaitSuccess(setOf(DIRECTIONS_TOOL), mark3, TOOL_TIMEOUT_MS, "maps_transit")
        Log.i(TAG, "transit result: ${transit.result}")
        assertEquals("directions tool should report transit mode: ${transit.result}", "transit", transit.result.optString("mode"))
        assertTrue("directions tool should report navigation started: ${transit.result}", transit.result.optBoolean("nav_started", true))
        assertTrue("Maps should still be on the transit route-detail screen", isTransitRouteDetail())
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 3 ok")

        // ---- Step 4: driving; expect drive mode + live turn-by-turn navigation ----
        val mark4 = toolResults.size
        sendVoice(chatViewModel, "Actually, just this time can you get driving directions for me?")
        awaitRouteLeftThenReached("live driving navigation", "maps_not_driving", mark4) { isActiveNavigation() && !device.hasObject(TRIP_DETAILS_FOOTER) }
        val drive = awaitSuccess(setOf(DIRECTIONS_TOOL), mark4, TOOL_TIMEOUT_MS, "maps_drive")
        Log.i(TAG, "drive result: ${drive.result}")
        val driveMode = drive.result.optString("mode")
        assertTrue("directions tool should report driving mode, got '$driveMode': ${drive.result}", driveMode in setOf("drive", "driving"))
        assertTrue("directions tool should report navigation started: ${drive.result}", drive.result.optBoolean("nav_started", true))
        assertTrue("Maps should still be in live driving navigation", isActiveNavigation() && !device.hasObject(TRIP_DETAILS_FOOTER))
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 4 ok")

        // ---- Step 5: the assistant's last message names the second address ----
        val last = lastAssistantMessage(chatViewModel)
        assertTrue(
            "last assistant message should mention ${city.secondaryAddress}: '$last'",
            last.contains(city.secondaryAddress, ignoreCase = true) || last.contains(city.secondaryAddressShort, ignoreCase = true)
        )
        Log.i(TAG, "step 5 ok: '$last'")
    }
}
