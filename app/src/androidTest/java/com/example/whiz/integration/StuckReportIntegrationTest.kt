package com.example.whiz.integration

import android.app.KeyguardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.ToolFlowIntegrationTest
import com.example.whiz.di.AppModule
import com.example.whiz.test_helpers.SkipOnCIOrEmulatorRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A screen agent tool that fails on an unexpected screen must hand the model what it needs to
 * recover: the goal, the failed step, the foreground app and the live interactable element list
 * (same `[n]` ids as agent_get_ui, valid for agent_click right away). This drives a tool straight
 * into a deterministic failure on the real device and checks the `stuck` block on its result.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class StuckReportIntegrationTest : ToolFlowIntegrationTest() {

    @get:Rule(order = 3)
    val skipOnCI = SkipOnCIOrEmulatorRule()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        startToolResultCollector()
    }

    @After
    fun cleanup() {
        stopToolResultCollector()
        runBlocking { cleanupTrackedChats() }
    }

    @Test
    fun failedScreenAgentTool_resultCarriesStuckBlockWithClickableIds(): Unit = runBlocking {
        // Screen agent tools park on the unlock gate while the keyguard is up, so a locked phone would
        // only show as a 60s timeout below. Say what is actually wrong.
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertFalse("the phone is locked; unlock it to run screen agent tools", keyguard.isKeyguardLocked)
        assertTrue("app did not launch", launchAppAndWaitForLoad()) // Whiz is in front, Maps is not open
        device.executeShellCommand("am force-stop com.google.android.apps.maps")

        val mark = toolResults.size
        // A results-list selection with no results list on screen fails deterministically.
        toolExecutor.executeToolFromJson(
            JSONObject(
                """{"type":"tool_execution","tool":"agent_select_location_from_list",
                    "request_id":"stuck_test_${System.currentTimeMillis()}",
                    "params":{"fragment":"zzz-no-such-place"}}"""
            )
        )
        val res = awaitResult("agent_select_location_from_list", mark, 60_000, "stuck_no_result")
        assertTrue("tool should have reported failure: ${res.result}", res.result.has("success") && !res.result.getBoolean("success"))

        val stuck = res.result.optJSONObject("stuck")
            ?: failWithScreenshot("failed tool result has no 'stuck' block: ${res.result}", "stuck_block_missing")
        assertEquals("Open one result from the Google Maps results list", stuck.getString("goal"))
        assertTrue("failed_step should be set", stuck.getString("failed_step").isNotBlank())
        assertTrue("foreground_app should be set: $stuck", stuck.has("foreground_app"))
        assertTrue("recovery should name the tool", stuck.getString("recovery").contains("agent_select_location_from_list"))
        val screen = stuck.optString("screen")
        assertTrue("screen should list numbered elements, got: ${screen.take(300)}", Regex("""\[\d+]""").containsMatchIn(screen))
    }
}
