package com.example.whiz.tools

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.whiz.MainActivity
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.MessageDraftOverlayService
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed class ToolExecutionResult {
    data class Success(
        val toolName: String,
        val requestId: String,
        val result: JSONObject
    ) : ToolExecutionResult()
    
    data class Error(
        val toolName: String,
        val requestId: String,
        val error: String
    ) : ToolExecutionResult()

    data class Status(
        val toolName: String,
        val requestId: String,
        val status: String,
        val message: String
    ) : ToolExecutionResult()
}

@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenAgentTools: ScreenAgentTools,
    private val deviceControlTools: DeviceControlTools,
    private val userPreferences: com.example.whiz.data.preferences.UserPreferences,
    private val authRepository: com.example.whiz.data.auth.AuthRepository
) {
    private val TAG = "ToolExecutor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val toolsRequiringUnlock = setOf(
        "agent_launch_app",
        "agent_whatsapp_select_chat", "agent_whatsapp_send_message", "agent_whatsapp_draft_message",
        "agent_sms_select_chat", "agent_sms_draft_message", "agent_sms_send_message",
        "agent_play_youtube_music", "agent_queue_youtube_music", "agent_pause_youtube_music",
        "agent_search_google_maps_location", "agent_search_google_maps_phrase",
        "agent_get_google_maps_directions", "agent_recenter_google_maps",
        "agent_fullscreen_google_maps", "agent_select_location_from_list",
        "agent_press_call_button",
        "agent_save_calendar_event"
    )
    
    private val _toolResults = MutableSharedFlow<ToolExecutionResult>()
    val toolResults: SharedFlow<ToolExecutionResult> = _toolResults.asSharedFlow()
    
    fun executeToolFromJson(
        toolRequest: JSONObject,
        voiceManager: com.example.whiz.ui.viewmodels.VoiceManager? = null,
        chatViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    ) {
        scope.launch {
            try {
                Log.i(TAG, "🎯 TOOL EXECUTOR: Starting execution")
                Log.i(TAG, "🎯 Full request: ${toolRequest.toString(2)}")

                val toolName = toolRequest.getString("tool")
                val requestId = toolRequest.getString("request_id")
                val params = if (toolRequest.has("params")) {
                    toolRequest.getJSONObject("params")
                } else {
                    JSONObject()
                }

                Log.i(TAG, "🎯 Executing tool: $toolName with requestId: $requestId")
                Log.i(TAG, "🎯 Tool params: ${params.toString(2)}")

                // Check if device is locked and tool requires unlock
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked && toolName in toolsRequiringUnlock) {
                    Log.i(TAG, "🔒 Device is locked and tool $toolName requires unlock - showing unlock prompt and waiting")
                    val unlockCallback = MainActivity.requestUnlockCallback
                    if (unlockCallback == null) {
                        Log.e(TAG, "🔒 No unlock callback available - MainActivity may not be active")
                        _toolResults.emit(ToolExecutionResult.Error(
                            toolName = toolName,
                            requestId = requestId,
                            error = "Phone is locked and cannot show unlock prompt. Please unlock your phone and try again."
                        ))
                        return@launch
                    }

                    // Notify server that we're waiting for unlock so it can extend its timeout
                    _toolResults.emit(ToolExecutionResult.Status(
                        toolName = toolName,
                        requestId = requestId,
                        status = "waiting_for_unlock",
                        message = "Phone is locked. Waiting for user to unlock."
                    ))

                    // Suspend and wait for user to unlock (or cancel), with 60s timeout
                    val unlocked = withTimeoutOrNull(60_000L) {
                        suspendCancellableCoroutine<Boolean> { cont ->
                            unlockCallback(
                                { // onSuccess
                                    Log.i(TAG, "🔓 User unlocked device - continuing with tool $toolName")
                                    if (cont.isActive) cont.resume(true)
                                },
                                { // onCancelled
                                    Log.i(TAG, "🔒 User cancelled unlock - aborting tool $toolName")
                                    if (cont.isActive) cont.resume(false)
                                }
                            )
                        }
                    }

                    if (unlocked != true) {
                        val reason = if (unlocked == null) "Unlock timed out" else "User cancelled unlock"
                        Log.i(TAG, "🔒 $reason for tool $toolName")
                        _toolResults.emit(ToolExecutionResult.Error(
                            toolName = toolName,
                            requestId = requestId,
                            error = "Phone is locked. Please unlock your phone to use this feature."
                        ))
                        return@launch
                    }

                    // Small delay after unlock to let the system settle
                    delay(500)
                }

                when (toolName) {
                    "agent_launch_app" -> {
                        Log.i(TAG, "🎯 Matched agent_launch_app tool, calling executeAppLauncher")
                        executeAppLauncher(requestId, params)
                    }
                    "agent_whatsapp_select_chat" -> {
                        executeWhatsAppSelectChat(requestId, params)
                    }
                    "agent_whatsapp_send_message" -> {
                        executeWhatsAppSendMessage(requestId, params)
                    }
                    "agent_whatsapp_draft_message" -> {
                        executeWhatsAppDraftMessage(requestId, params)
                    }
                    "agent_sms_select_chat" -> {
                        executeSMSSelectChat(requestId, params)
                    }
                    "agent_sms_draft_message" -> {
                        executeSMSDraftMessage(requestId, params)
                    }
                    "agent_sms_send_message" -> {
                        executeSMSSendMessage(requestId, params)
                    }
                    "agent_disable_continuous_listening" -> {
                        executeDisableContinuousListening(requestId, voiceManager)
                    }
                    "agent_set_tts_enabled" -> {
                        executeSetTTSEnabled(requestId, params, chatViewModel)
                    }
                    "agent_close_app" -> {
                        executeCloseApp(requestId)
                    }
                    "agent_dismiss_draft" -> {
                        executeDismissDraft(requestId)
                    }
                    "agent_play_youtube_music" -> {
                        executePlayYouTubeMusic(requestId, params)
                    }
                    "agent_queue_youtube_music" -> {
                        executeQueueYouTubeMusic(requestId, params)
                    }
                    "agent_pause_youtube_music" -> {
                        Log.i(TAG, "Matched agent_pause_youtube_music tool")
                        executePauseYouTubeMusic(requestId)
                    }
                    "agent_search_google_maps_location" -> {
                        executeSearchGoogleMapsLocation(requestId, params)
                    }
                    "agent_search_google_maps_phrase" -> {
                        executeSearchGoogleMapsPhrase(requestId, params)
                    }
                    "agent_get_google_maps_directions" -> {
                        executeGetGoogleMapsDirections(requestId, params)
                    }
                    "agent_recenter_google_maps" -> {
                        executeRecenterGoogleMaps(requestId, params)
                    }
                    "agent_fullscreen_google_maps" -> {
                        executeFullscreenGoogleMaps(requestId, params)
                    }
                    "agent_select_location_from_list" -> {
                        executeSelectLocationFromList(requestId, params)
                    }
                    // ========== Device Control Tools (direct intents/APIs) ==========
                    "agent_set_alarm" -> {
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.setAlarm(it) }
                    }
                    "agent_set_timer" -> {
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.setTimer(it) }
                    }
                    "agent_dismiss_alarm" -> {
                        executeDismissAlarm(requestId)
                    }
                    "agent_dismiss_timer" -> {
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.dismissTimer() }
                    }
                    "agent_get_next_alarm" -> {
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.getNextAlarm() }
                    }
                    "agent_delete_alarm" -> {
                        executeDeleteAlarm(requestId, params)
                    }
                    "agent_dismiss_amdroid_alarm" -> {
                        executeDismissAmdroidAlarm(requestId)
                    }
                    "agent_toggle_flashlight" -> {
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.toggleFlashlight(it) }
                    }
                    "agent_draft_calendar_event" -> {
                        executeDraftCalendarEvent(requestId, params)
                    }
                    "agent_save_calendar_event" -> {
                        executeSaveCalendarEvent(requestId, params)
                    }
                    "agent_dial_phone_number" -> {
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.dialPhoneNumber(it) }
                    }
                    "agent_press_call_button" -> {
                        executePhoneCallButton(requestId, params)
                    }
                    "agent_set_volume" -> {
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.setVolume(it) }
                    }
                    "agent_lookup_phone_contacts" -> {
                        // Check contacts permission and prompt user if needed
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                            != PackageManager.PERMISSION_GRANTED) {
                            val permCallback = MainActivity.requestContactsPermissionCallback
                            if (permCallback != null) {
                                Log.i(TAG, "📇 READ_CONTACTS not granted - showing permission dialog")
                                _toolResults.emit(ToolExecutionResult.Status(
                                    toolName = toolName,
                                    requestId = requestId,
                                    status = "waiting_for_contacts_permission",
                                    message = "Contacts permission required. Waiting for user to grant."
                                ))
                                val granted = withTimeoutOrNull(60_000L) {
                                    suspendCancellableCoroutine<Boolean> { cont ->
                                        permCallback(
                                            { // onGranted
                                                Log.i(TAG, "📇 User granted contacts permission")
                                                if (cont.isActive) cont.resume(true)
                                            },
                                            { // onDenied
                                                Log.i(TAG, "📇 User denied contacts permission")
                                                if (cont.isActive) cont.resume(false)
                                            }
                                        )
                                    }
                                }
                                if (granted != true) {
                                    val reason = if (granted == null) "Permission request timed out" else "User denied permission"
                                    Log.i(TAG, "📇 $reason for contacts lookup")
                                }
                                // Proceed regardless - lookupPhoneContacts handles missing permission gracefully
                            }
                        }
                        executeDeviceControlTool(toolName, requestId, params) { deviceControlTools.lookupPhoneContacts(it) }
                    }
                    else -> {
                        Log.w(TAG, "Unknown tool: $toolName")
                        _toolResults.emit(
                            ToolExecutionResult.Error(
                                toolName = toolName,
                                requestId = requestId,
                                error = "Unknown tool: $toolName"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing tool request", e)
                try {
                    val requestId = if (toolRequest.has("request_id")) {
                        toolRequest.getString("request_id")
                    } else {
                        "unknown"
                    }
                    val toolName = if (toolRequest.has("tool")) {
                        toolRequest.getString("tool")
                    } else {
                        "unknown"
                    }
                    
                    _toolResults.emit(
                        ToolExecutionResult.Error(
                            toolName = toolName,
                            requestId = requestId,
                            error = "Error executing tool: ${e.message}"
                        )
                    )
                } catch (emitError: Exception) {
                    Log.e(TAG, "Error emitting tool error result", emitError)
                }
            }
        }
    }
    
    private fun getToolsForPackage(packageName: String): List<JSONObject> {
        return when (packageName) {
            "com.google.android.apps.maps" -> listOf(
                JSONObject().apply {
                    put("name", "agent_search_google_maps_location")
                    put("description", "Search for a specific address or place name in Google Maps and show results. After successfully searching, the tool returns the exact address of the selected location - ALWAYS read this address back to the user to confirm the correct place was found.")
                },
                JSONObject().apply {
                    put("name", "agent_search_google_maps_phrase")
                    put("description", "Search for places by category/phrase (e.g., 'coffee shops nearby', 'gas stations') in Google Maps")
                },
                JSONObject().apply {
                    put("name", "agent_get_google_maps_directions")
                    put("description", "Get directions to the currently selected location. Must call search first.")
                },
                JSONObject().apply {
                    put("name", "agent_select_location_from_list")
                    put("description", "Select a specific location from search results by position or name fragment. After successfully selecting, the tool returns the exact address of the selected location - ALWAYS read this address back to the user to confirm the correct place was selected.")
                },
                JSONObject().apply {
                    put("name", "agent_recenter_google_maps")
                    put("description", "Re-center the map to your current location")
                },
                JSONObject().apply {
                    put("name", "agent_fullscreen_google_maps")
                    put("description", "Bring Google Maps to fullscreen/foreground when it's running in the background or shown as a small overlay")
                }
            )
            "com.google.android.apps.youtube.music" -> listOf(
                JSONObject().apply {
                    put("name", "agent_play_youtube_music")
                    put("description", "Play a song or artist in YouTube Music")
                },
                JSONObject().apply {
                    put("name", "agent_queue_youtube_music")
                    put("description", "Add a song or artist to the YouTube Music queue")
                },
                JSONObject().apply {
                    put("name", "agent_pause_youtube_music")
                    put("description", "Pause or resume YouTube Music playback")
                }
            )
            "com.whatsapp" -> listOf(
                JSONObject().apply {
                    put("name", "agent_whatsapp_select_chat")
                    put("description", "Select a specific chat in WhatsApp by contact/group name")
                },
                JSONObject().apply {
                    put("name", "agent_whatsapp_draft_message")
                    put("description", "Draft a message for user review before sending")
                },
                JSONObject().apply {
                    put("name", "agent_whatsapp_send_message")
                    put("description", "Send the drafted message (must draft first)")
                }
            )
            else -> {
                // Check if this is the default SMS app
                val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
                if (packageName == defaultSmsPackage) {
                    Log.i(TAG, "Detected SMS app: $packageName (default SMS app)")
                    // Save the SMS app package for future reference
                    authRepository.saveDefaultSmsAppPackage(packageName)

                    listOf(
                        JSONObject().apply {
                            put("name", "agent_sms_select_chat")
                            put("description", "Select a specific SMS conversation by contact name")
                        },
                        JSONObject().apply {
                            put("name", "agent_sms_draft_message")
                            put("description", "Draft an SMS message for user review before sending")
                        },
                        JSONObject().apply {
                            put("name", "agent_sms_send_message")
                            put("description", "Send the drafted SMS message (must draft first)")
                        }
                    )
                } else {
                    emptyList()
                }
            }
        }
    }

    private suspend fun executeAppLauncher(requestId: String, params: JSONObject) {
        try {
            Log.i(TAG, "🚀 EXECUTE APP LAUNCHER STARTED")
            val appName = params.getString("app_name")
            Log.i(TAG, "🚀 Launching app: $appName")

            val result = screenAgentTools.launchApp(appName)
            Log.i(TAG, "🚀 Launch result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("app_name", result.appName)
                result.packageName?.let {
                    put("package_name", it)
                    // Add available tools for this app
                    val availableTools = getToolsForPackage(it)
                    if (availableTools.isNotEmpty()) {
                        put("available_tools", org.json.JSONArray(availableTools))
                    }
                }
                result.error?.let { put("error", it) }
                put("overlay_started", result.overlayStarted)
                put("overlayPermissionRequired", result.overlayPermissionRequired)
            }

            Log.i(TAG, "📤 [TOOL_RESULT] About to emit tool result for requestId=$requestId")
            Log.i(TAG, "📤 [TOOL_RESULT] Result JSON: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_launch_app",
                    requestId = requestId,
                    result = resultJson
                )
            )

            Log.i(TAG, "📤 [TOOL_RESULT] Successfully emitted tool result for requestId=$requestId")

        } catch (e: Exception) {
            Log.e(TAG, "Error executing app launcher", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_launch_app",
                    requestId = requestId,
                    error = "Failed to launch app: ${e.message}"
                )
            )
        }
    }
    
    private suspend fun executeWhatsAppSelectChat(requestId: String, params: JSONObject) {
        try {
            val chatName = params.getString("chat_name")
            Log.i(TAG, "Selecting WhatsApp chat: $chatName")
            
            val result = screenAgentTools.selectWhatsAppChat(chatName)
            Log.i(TAG, "WhatsApp select chat result: success=${result.success}, error=${result.error}")
            
            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.chatName?.let { put("chat_name", it) }
                result.error?.let { put("error", it) }
            }
            
            Log.i(TAG, "[TOOL_RESULT] WhatsApp select chat result for requestId=$requestId: ${resultJson.toString(2)}")
            
            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_whatsapp_select_chat",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp select chat", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_whatsapp_select_chat",
                    requestId = requestId,
                    error = "Failed to select WhatsApp chat: ${e.message}"
                )
            )
        }
    }
    
    private suspend fun executeWhatsAppDraftMessage(requestId: String, params: JSONObject) {
        Log.i(TAG, "executeWhatsAppDraftMessage called with requestId: $requestId")
        Log.i(TAG, "Params received: ${params.toString(2)}")
        try {
            val message = params.getString("message")
            val previousText = if (params.has("previous_text")) params.getString("previous_text") else null
            val chatName = if (params.has("chat_name")) params.getString("chat_name") else null
            Log.i(TAG, "Drafting WhatsApp message: message='$message', previousText='$previousText', chatName='$chatName'")

            val result = screenAgentTools.draftWhatsAppMessage(message, previousText, chatName)

            val resultJson = JSONObject().apply {
                put("success", result.success)
                result.message?.let { put("message", it) }
                result.error?.let { put("error", it) }
                put("overlay_shown", result.overlayShown)
                // Add reminder for the LLM to wait for user confirmation
                put("important_note", "WAIT FOR USER CONFIRMATION before sending. Do NOT call agent_whatsapp_send_message until the user explicitly confirms they want to send the message. The draft is now displayed to the user for review.")
            }
            
            Log.i(TAG, "[TOOL_RESULT] WhatsApp draft message result for requestId=$requestId: ${resultJson.toString(2)}")
            
            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_whatsapp_draft_message",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp draft message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_whatsapp_draft_message",
                    requestId = requestId,
                    error = "Failed to draft WhatsApp message: ${e.message}"
                )
            )
        }
    }
    
    
    private suspend fun executeWhatsAppSendMessage(requestId: String, params: JSONObject) {
        try {
            val message = params.getString("message")
            Log.d(TAG, "Sending WhatsApp message: $message")

            val result = screenAgentTools.sendWhatsAppMessage(message)

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] WhatsApp send message result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_whatsapp_send_message",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp send message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_whatsapp_send_message",
                    requestId = requestId,
                    error = "Failed to send WhatsApp message: ${e.message}"
                )
            )
        }
    }

    // ========== SMS Tool Execution Methods ==========

    private suspend fun executeSMSSelectChat(requestId: String, params: JSONObject) {
        try {
            val contactName = params.getString("contact_name")
            Log.i(TAG, "Selecting SMS chat: $contactName")

            val result = screenAgentTools.selectSMSChat(contactName)

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.contactName?.let { put("contact_name", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] SMS select chat result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_sms_select_chat",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing SMS select chat", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_sms_select_chat",
                    requestId = requestId,
                    error = "Failed to select SMS chat: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeSMSDraftMessage(requestId: String, params: JSONObject) {
        Log.i(TAG, "executeSMSDraftMessage called with requestId: $requestId")
        Log.i(TAG, "Params received: ${params.toString(2)}")
        try {
            val message = params.getString("message")
            val previousText = if (params.has("previous_text")) params.getString("previous_text") else null
            val contactName = if (params.has("contact_name")) params.getString("contact_name") else null

            Log.i(TAG, "Drafting SMS message: message='$message', previousText='$previousText', contactName='$contactName'")

            val result = screenAgentTools.draftSMSMessage(message, previousText, contactName)

            val resultJson = JSONObject().apply {
                put("success", result.success)
                result.message?.let { put("message", it) }
                result.error?.let { put("error", it) }
                put("overlay_shown", result.overlayShown)
                // Add reminder for the LLM to wait for user confirmation
                put("important_note", "WAIT FOR USER CONFIRMATION before sending. Do NOT call agent_sms_send_message until the user explicitly confirms they want to send the message. The draft is now displayed to the user for review.")
            }

            Log.i(TAG, "[TOOL_RESULT] SMS draft message result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_sms_draft_message",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing SMS draft message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_sms_draft_message",
                    requestId = requestId,
                    error = "Failed to draft SMS message: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeSMSSendMessage(requestId: String, params: JSONObject) {
        try {
            val message = params.getString("message")
            Log.d(TAG, "Sending SMS message: $message")

            val result = screenAgentTools.sendSMSMessage(message)

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] SMS send message result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_sms_send_message",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing SMS send message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_sms_send_message",
                    requestId = requestId,
                    error = "Failed to send SMS message: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeDisableContinuousListening(
        requestId: String,
        voiceManager: com.example.whiz.ui.viewmodels.VoiceManager?
    ) {
        try {
            if (voiceManager == null) {
                Log.e(TAG, "VoiceManager not provided for disable_continuous_listening")
                _toolResults.emit(
                    ToolExecutionResult.Error(
                        toolName = "agent_disable_continuous_listening",
                        requestId = requestId,
                        error = "VoiceManager not available"
                    )
                )
                return
            }

            // Check if bubble mode is active
            val isBubbleActive = com.example.whiz.services.BubbleOverlayService.isActive
            Log.i(TAG, "Disabling continuous listening (bubble active: $isBubbleActive)")

            if (isBubbleActive) {
                // In bubble mode, switch to MIC_OFF mode
                Log.i(TAG, "Bubble mode active - switching to MIC_OFF mode")
                com.example.whiz.services.BubbleOverlayService.setMode(com.example.whiz.services.ListeningMode.MIC_OFF)
            } else {
                // Not in bubble mode, use normal voiceManager
                voiceManager.updateContinuousListeningEnabled(false)
            }

            val resultJson = JSONObject().apply {
                put("success", true)
                put("message", "Continuous listening disabled")
                put("bubble_mode_active", isBubbleActive)
            }

            Log.i(TAG, "[TOOL_RESULT] Disable continuous listening result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_disable_continuous_listening",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error disabling continuous listening", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_disable_continuous_listening",
                    requestId = requestId,
                    error = "Failed to disable continuous listening: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeSetTTSEnabled(
        requestId: String,
        params: JSONObject,
        chatViewModel: com.example.whiz.ui.viewmodels.ChatViewModel?
    ) {
        try {
            if (chatViewModel == null) {
                Log.e(TAG, "ChatViewModel not provided for set_tts_enabled")
                _toolResults.emit(
                    ToolExecutionResult.Error(
                        toolName = "agent_set_tts_enabled",
                        requestId = requestId,
                        error = "ChatViewModel not available"
                    )
                )
                return
            }

            val enabled = params.getBoolean("enabled")

            // Check if bubble mode is active
            val isBubbleActive = com.example.whiz.services.BubbleOverlayService.isActive
            Log.i(TAG, "Setting TTS enabled to: $enabled (bubble active: $isBubbleActive)")

            // Always update VoiceManager state so observers are notified
            chatViewModel.setVoiceResponseEnabled(enabled)
            Log.i(TAG, "Updated VoiceManager TTS state to: $enabled")

            if (isBubbleActive) {
                // In bubble mode, also directly update bubble mode for immediate effect
                // (The bubble observer will also react, but direct update ensures it happens immediately)
                if (enabled) {
                    Log.i(TAG, "Bubble mode active - switching to TTS_WITH_LISTENING mode")
                    com.example.whiz.services.BubbleOverlayService.setMode(com.example.whiz.services.ListeningMode.TTS_WITH_LISTENING)
                } else {
                    Log.i(TAG, "Bubble mode active - switching to CONTINUOUS_LISTENING mode")
                    com.example.whiz.services.BubbleOverlayService.setMode(com.example.whiz.services.ListeningMode.CONTINUOUS_LISTENING)
                }
            }

            val resultJson = JSONObject().apply {
                put("success", true)
                put("enabled", enabled)
                put("message", if (enabled) "TTS enabled" else "TTS disabled")
                put("bubble_mode_active", isBubbleActive)
            }

            Log.i(TAG, "[TOOL_RESULT] Set TTS enabled result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_set_tts_enabled",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error setting TTS enabled", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_set_tts_enabled",
                    requestId = requestId,
                    error = "Failed to set TTS enabled: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeCloseApp(requestId: String) {
        try {
            Log.i(TAG, "Executing close app command")

            val resultJson = JSONObject().apply {
                put("success", true)
                put("message", "App closing")
            }

            Log.i(TAG, "[TOOL_RESULT] Close app result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_close_app",
                    requestId = requestId,
                    result = resultJson
                )
            )

            // Short delay to allow the result to be sent before closing
            delay(100)

            // Use static callback to tell MainActivity to finish and remove from recents
            Log.i(TAG, "Invoking finishAndRemoveTaskCallback on MainActivity")
            val callback = com.example.whiz.MainActivity.finishAndRemoveTaskCallback
            if (callback != null) {
                // Run on main thread since finishAndRemoveTask() must be called from main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback.invoke()
                }
                Log.i(TAG, "finishAndRemoveTaskCallback invoked")
            } else {
                Log.w(TAG, "finishAndRemoveTaskCallback is null - MainActivity may not be active")
            }

            // Short delay to allow MainActivity to finish
            delay(150)

            // Stop bubble overlay service if active
            Log.i(TAG, "Stopping BubbleOverlayService")
            BubbleOverlayService.stop(context)

            // Short delay to allow service to stop cleanly
            delay(50)

            // Kill the process to close everything
            Log.i(TAG, "Killing process to close app")
            android.os.Process.killProcess(android.os.Process.myPid())

        } catch (e: Exception) {
            Log.e(TAG, "Error executing close app", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_close_app",
                    requestId = requestId,
                    error = "Failed to close app: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeDismissDraft(requestId: String) {
        try {
            val wasActive = MessageDraftOverlayService.isActive
            Log.i(TAG, "Dismissing draft overlay (wasActive=$wasActive)")

            if (wasActive) {
                MessageDraftOverlayService.stop(context)
            }

            val resultJson = JSONObject().apply {
                put("success", true)
                put("message", if (wasActive) "Draft overlay dismissed" else "No active draft to dismiss")
            }

            Log.i(TAG, "[TOOL_RESULT] Dismiss draft result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_dismiss_draft",
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing draft overlay", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_dismiss_draft",
                    requestId = requestId,
                    error = "Failed to dismiss draft: ${e.message}"
                )
            )
        }
    }

    private suspend fun executePlayYouTubeMusic(requestId: String, params: JSONObject) {
        try {
            val query = params.getString("query")
            val contentType = params.optString("content_type", "song")
            Log.i(TAG, "Playing on YouTube Music: $query (contentType=$contentType)")

            // The server has already decided to use YouTube Music by calling this tool
            // No need to check preferences here - just execute the action
            val result = screenAgentTools.playYouTubeMusicSong(query, contentType)

            Log.i(TAG, "YouTube Music play result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                put("music_app_used", "youtube_music")
                result.query?.let { put("query", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Music play result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_play_youtube_music",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing music play", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_play_youtube_music",
                    requestId = requestId,
                    error = "Failed to play song: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeQueueYouTubeMusic(requestId: String, params: JSONObject) {
        try {
            val query = params.getString("query")
            Log.i(TAG, "Queueing song on YouTube Music: $query")

            // The server has already decided to use YouTube Music by calling this tool
            // No need to check preferences here - just execute the action
            val result = screenAgentTools.queueYouTubeMusicSong(query)

            Log.i(TAG, "YouTube Music queue result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                put("music_app_used", "youtube_music")
                result.query?.let { put("query", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Music queue result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_queue_youtube_music",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing music queue", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_queue_youtube_music",
                    requestId = requestId,
                    error = "Failed to queue song: ${e.message}"
                )
            )
        }
    }

    private suspend fun executePauseYouTubeMusic(requestId: String) {
        try {
            Log.i(TAG, "Pausing/resuming YouTube Music")

            val result = screenAgentTools.pauseYouTubeMusic()

            Log.i(TAG, "YouTube Music pause result: success=${result.success}, state=${result.nowPlaying}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.nowPlaying?.let { put("state", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] YouTube Music pause result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_pause_youtube_music",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing YouTube Music pause", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_pause_youtube_music",
                    requestId = requestId,
                    error = "Failed to pause/resume music: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeSearchGoogleMapsLocation(requestId: String, params: JSONObject) {
        try {
            val address = params.getString("address")
            Log.i(TAG, "Searching Google Maps for location: $address")

            val result = screenAgentTools.searchGoogleMapsLocation(address)

            Log.i(TAG, "Google Maps search result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.location?.let { put("location", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Google Maps search result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_search_google_maps_location",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps search", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_search_google_maps_location",
                    requestId = requestId,
                    error = "Failed to search location: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeSearchGoogleMapsPhrase(requestId: String, params: JSONObject) {
        try {
            val searchPhrase = params.getString("search_phrase")
            Log.i(TAG, "Searching Google Maps with phrase: $searchPhrase")

            val result = screenAgentTools.searchGoogleMapsPhrase(searchPhrase)

            Log.i(TAG, "Google Maps phrase search result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.location?.let { put("search_phrase", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Google Maps phrase search result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_search_google_maps_phrase",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps phrase search", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_search_google_maps_phrase",
                    requestId = requestId,
                    error = "Failed to search phrase: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeGetGoogleMapsDirections(requestId: String, params: JSONObject) {
        try {
            val mode = if (params.has("mode")) params.getString("mode") else null
            val search = if (params.has("search")) params.getString("search") else null
            val position = if (params.has("position")) params.getInt("position") else null
            val fragment = if (params.has("fragment")) params.getString("fragment") else null
            Log.i(TAG, "Getting Google Maps directions with mode: ${mode ?: "default"}, search: $search, position: $position, fragment: $fragment")

            val result = screenAgentTools.getGoogleMapsDirections(mode, search, position, fragment)

            Log.i(TAG, "Google Maps directions result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.mode?.let { put("mode", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Google Maps directions result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_get_google_maps_directions",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps directions", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_get_google_maps_directions",
                    requestId = requestId,
                    error = "Failed to get directions: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeRecenterGoogleMaps(requestId: String, params: JSONObject) {
        try {
            Log.i(TAG, "Re-centering Google Maps")

            val result = screenAgentTools.recenterGoogleMaps()

            Log.i(TAG, "Google Maps recenter result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Google Maps recenter result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_recenter_google_maps",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps recenter", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_recenter_google_maps",
                    requestId = requestId,
                    error = "Failed to recenter map: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeFullscreenGoogleMaps(requestId: String, params: JSONObject) {
        try {
            Log.i(TAG, "Fullscreening Google Maps")

            val result = screenAgentTools.fullscreenGoogleMaps()

            Log.i(TAG, "Google Maps fullscreen result: success=${result.success}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Google Maps fullscreen result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_fullscreen_google_maps",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps fullscreen", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_fullscreen_google_maps",
                    requestId = requestId,
                    error = "Failed to fullscreen map: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeSelectLocationFromList(requestId: String, params: JSONObject) {
        try {
            val position = if (params.has("position")) params.getInt("position") else null
            val fragment = if (params.has("fragment")) params.getString("fragment") else null

            // Default to position 1 if neither provided
            val finalPosition = position ?: if (fragment == null) 1 else null
            val finalFragment = if (finalPosition == null) fragment else null

            val selectionDesc = if (finalPosition != null) "position $finalPosition" else "fragment '$finalFragment'"
            Log.i(TAG, "Selecting location from list: $selectionDesc")

            val result = screenAgentTools.selectLocationFromList(finalPosition, finalFragment)

            Log.i(TAG, "Select location result: success=${result.success}, location=${result.location}, error=${result.error}")

            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("action", result.action)
                result.location?.let { put("location", it) }
                result.error?.let { put("error", it) }
            }

            Log.i(TAG, "[TOOL_RESULT] Select location result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_select_location_from_list",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing select location from list", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_select_location_from_list",
                    requestId = requestId,
                    error = "Failed to select location: ${e.message}"
                )
            )
        }
    }

    /**
     * Press the call button in the dialer via accessibility service.
     */
    private suspend fun executePhoneCallButton(requestId: String, params: JSONObject) {
        try {
            val expectedNumber = if (params.has("expected_number")) params.getString("expected_number") else null
            val speakerphone = if (params.has("speakerphone")) params.getBoolean("speakerphone") else true
            Log.i(TAG, "Pressing call button, expectedNumber=$expectedNumber, speakerphone=$speakerphone")

            val result = screenAgentTools.pressCallButton(expectedNumber, speakerphone)

            val resultJson = JSONObject().apply {
                put("success", result.success)
                result.dialedNumber?.let { put("dialed_number", it) }
                result.error?.let { put("error", it) }
                if (result.speakerphoneEnabled) put("speakerphone_enabled", true)
            }

            Log.i(TAG, "[TOOL_RESULT] agent_press_call_button result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_press_call_button",
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing press call button", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_press_call_button",
                    requestId = requestId,
                    error = "Failed to press call button: ${e.message}"
                )
            )
        }
    }

    /**
     * Draft a calendar event. Always dismisses any existing calendar draft first
     * (handles both redrafts and cases where the user was drafting outside the app).
     */
    private suspend fun executeDraftCalendarEvent(requestId: String, params: JSONObject) {
        try {
            // Always dismiss any existing calendar draft before launching a new one.
            // This handles both explicit redrafts and cases where the user was already
            // drafting an event outside of Whiz (which would show a discard dialog).
            Log.i(TAG, "Dismissing any existing calendar draft before drafting new event")
            val dismissed = deviceControlTools.dismissCalendarDraft()
            Log.i(TAG, "dismissCalendarDraft result: $dismissed")

            val resultJson = deviceControlTools.draftCalendarEvent(params)
            Log.i(TAG, "[TOOL_RESULT] agent_draft_calendar_event result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_draft_calendar_event",
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing agent_draft_calendar_event", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_draft_calendar_event",
                    requestId = requestId,
                    error = "Failed to draft calendar event: ${e.message}"
                )
            )
        }
    }

    /**
     * Save calendar event via ContentProvider insert, then dismiss the draft UI.
     * Checks WRITE_CALENDAR permission first and prompts user if needed.
     */
    private suspend fun executeSaveCalendarEvent(requestId: String, params: JSONObject) {
        try {
            Log.i(TAG, "Saving calendar event via ContentProvider")

            // Check READ_CALENDAR + WRITE_CALENDAR permission and prompt user if needed
            val hasCalendarPerms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            if (!hasCalendarPerms) {
                val permCallback = MainActivity.requestCalendarPermissionCallback
                if (permCallback != null) {
                    Log.i(TAG, "Calendar permissions not granted - showing permission dialog")

                    // If bubble overlay is active, we need to bring MainActivity to foreground
                    // so the user can see the permission dialog
                    val wasBubbleActive = BubbleOverlayService.isActive
                    if (wasBubbleActive) {
                        Log.i(TAG, "Bubble overlay active - stopping bubble and bringing MainActivity to foreground for permission dialog")
                        BubbleOverlayService.stop(context)
                        val bringToFrontIntent = android.content.Intent(context, MainActivity::class.java).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        context.startActivity(bringToFrontIntent)
                        delay(500) // Let MainActivity come to foreground
                    }

                    _toolResults.emit(ToolExecutionResult.Status(
                        toolName = "agent_save_calendar_event",
                        requestId = requestId,
                        status = "waiting_for_calendar_permission",
                        message = "Calendar permission required. Waiting for user to grant."
                    ))
                    val granted = withTimeoutOrNull(60_000L) {
                        suspendCancellableCoroutine<Boolean> { cont ->
                            permCallback(
                                { // onGranted
                                    Log.i(TAG, "User granted calendar permission")
                                    if (cont.isActive) cont.resume(true)
                                },
                                { // onDenied
                                    Log.i(TAG, "User denied calendar permission")
                                    if (cont.isActive) cont.resume(false)
                                }
                            )
                        }
                    }

                    if (granted != true) {
                        val reason = if (granted == null) "Permission request timed out" else "User denied permission"
                        Log.i(TAG, "$reason for calendar save")
                        // Restore bubble if it was active
                        if (wasBubbleActive) {
                            BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
                            BubbleOverlayService.start(context)
                        }
                        _toolResults.emit(
                            ToolExecutionResult.Error(
                                toolName = "agent_save_calendar_event",
                                requestId = requestId,
                                error = "$reason. Cannot save calendar event without calendar permission."
                            )
                        )
                        return
                    }

                    // Wait for permission to propagate, then restore bubble overlay
                    if (wasBubbleActive) {
                        // Poll until permission is actually usable (up to 3 seconds)
                        val permWaitStart = System.currentTimeMillis()
                        while (System.currentTimeMillis() - permWaitStart < 3000) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                                Log.i(TAG, "Calendar permissions confirmed after ${System.currentTimeMillis() - permWaitStart}ms")
                                break
                            }
                            delay(200)
                        }
                        // Restart bubble overlay and bring calendar back to foreground
                        Log.i(TAG, "Restarting bubble overlay after permission grant")
                        BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
                        BubbleOverlayService.start(context)
                        // Bring Google Calendar back to foreground so bubble overlays on calendar, not chat
                        val calendarIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.calendar")
                        if (calendarIntent != null) {
                            calendarIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            context.startActivity(calendarIntent)
                            Log.i(TAG, "Brought Google Calendar back to foreground")
                        }
                        delay(500)
                    }
                } else {
                    Log.w(TAG, "Calendar permissions not granted and no permission callback available")
                    _toolResults.emit(
                        ToolExecutionResult.Error(
                            toolName = "agent_save_calendar_event",
                            requestId = requestId,
                            error = "Calendar permission not granted and cannot request it (no active Activity)."
                        )
                    )
                    return
                }
            }

            // Insert event via ContentProvider
            val resultJson = deviceControlTools.saveCalendarEventViaContentProvider(params)

            Log.i(TAG, "[TOOL_RESULT] agent_save_calendar_event result for requestId=$requestId: ${resultJson.toString(2)}")

            // On success, dismiss the Calendar draft UI, wait for sync, then show the event
            if (resultJson.optBoolean("success", false)) {
                Log.i(TAG, "ContentProvider insert succeeded, dismissing calendar draft UI")
                deviceControlTools.dismissCalendarDraft()

                val accountName = resultJson.optString("account_name", "")
                val accountType = resultJson.optString("account_type", "")
                val eventUri = resultJson.optString("event_uri", "")

                // Request a sync so Google Calendar's internal cache picks up our insert.
                // Without this, EventInfoActivity shows "The requested event was not found".
                // The sync can take 6-15+ seconds (JobScheduler delay + network), so we wait.
                try {
                    val account = android.accounts.Account(accountName, accountType)
                    val extras = android.os.Bundle().apply {
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    }
                    android.content.ContentResolver.requestSync(account, android.provider.CalendarContract.AUTHORITY, extras)
                    Log.i(TAG, "Requested calendar sync for account=$accountName ($accountType)")

                    // Wait for sync to start, then wait for it to finish
                    val syncStart = System.currentTimeMillis()
                    val syncTimeout = 20_000L // 20s — sync can take a while via JobScheduler

                    // Phase 1: wait for sync to become active
                    var syncStarted = false
                    while (System.currentTimeMillis() - syncStart < syncTimeout) {
                        if (android.content.ContentResolver.isSyncActive(account, android.provider.CalendarContract.AUTHORITY)) {
                            syncStarted = true
                            Log.i(TAG, "Calendar sync started after ${System.currentTimeMillis() - syncStart}ms")
                            break
                        }
                        delay(200)
                    }

                    if (syncStarted) {
                        // Phase 2: wait for sync to complete
                        while (System.currentTimeMillis() - syncStart < syncTimeout) {
                            if (!android.content.ContentResolver.isSyncActive(account, android.provider.CalendarContract.AUTHORITY)) {
                                Log.i(TAG, "Calendar sync completed after ${System.currentTimeMillis() - syncStart}ms")
                                break
                            }
                            delay(200)
                        }
                    }

                    val elapsed = System.currentTimeMillis() - syncStart
                    if (elapsed >= syncTimeout) {
                        Log.w(TAG, "Calendar sync timed out after ${syncTimeout}ms, proceeding anyway")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Calendar sync wait failed (non-fatal): ${e.message}")
                }

                // Open the event in Google Calendar
                if (eventUri.isNotEmpty()) {
                    val uri = android.net.Uri.parse(eventUri)
                    val eventDetails = context.contentResolver.query(
                        uri, arrayOf("_id", "dtstart", "dtend"), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            Triple(
                                cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                                cursor.getLong(cursor.getColumnIndexOrThrow("dtstart")),
                                cursor.getLong(cursor.getColumnIndexOrThrow("dtend"))
                            )
                        } else null
                    }

                    if (eventDetails != null) {
                        val (eventId, beginTime, endTime) = eventDetails
                        Log.i(TAG, "Opening event (id=$eventId, begin=$beginTime, end=$endTime): $eventUri")
                        val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            setPackage("com.google.android.calendar")
                            putExtra("beginTime", beginTime)
                            putExtra("endTime", endTime)
                        }
                        context.startActivity(viewIntent)
                    } else {
                        Log.w(TAG, "Event not found in ContentProvider after insert: $eventUri")
                    }
                }
            }

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_save_calendar_event",
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing save calendar event", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_save_calendar_event",
                    requestId = requestId,
                    error = "Failed to save calendar event: ${e.message}"
                )
            )
        }
    }

    /**
     * Delete a scheduled alarm by finding it in the Clock app's alarm list.
     */
    private suspend fun executeDeleteAlarm(requestId: String, params: JSONObject) {
        try {
            Log.i(TAG, "Executing agent_delete_alarm")
            val resultJson = deviceControlTools.deleteAlarm(params)
            Log.i(TAG, "[TOOL_RESULT] agent_delete_alarm result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_delete_alarm",
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing agent_delete_alarm", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_delete_alarm",
                    requestId = requestId,
                    error = "Failed to delete alarm: ${e.message}"
                )
            )
        }
    }

    /**
     * Dismiss a currently ringing alarm via ACTION_DISMISS_ALARM, with audio verification.
     */
    private suspend fun executeDismissAlarm(requestId: String) {
        try {
            Log.i(TAG, "Executing agent_dismiss_alarm")
            val resultJson = deviceControlTools.dismissAlarm()
            Log.i(TAG, "[TOOL_RESULT] agent_dismiss_alarm result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_dismiss_alarm",
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing agent_dismiss_alarm", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_dismiss_alarm",
                    requestId = requestId,
                    error = "Failed to dismiss alarm: ${e.message}"
                )
            )
        }
    }

    /**
     * Dismiss a currently ringing AMdroid alarm via accessibility UI automation.
     */
    private suspend fun executeDismissAmdroidAlarm(requestId: String) {
        try {
            Log.i(TAG, "Executing agent_dismiss_amdroid_alarm")
            val resultJson = deviceControlTools.dismissAmdroidAlarm()
            Log.i(TAG, "[TOOL_RESULT] agent_dismiss_amdroid_alarm result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "agent_dismiss_amdroid_alarm",
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing agent_dismiss_amdroid_alarm", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "agent_dismiss_amdroid_alarm",
                    requestId = requestId,
                    error = "Failed to dismiss AMdroid alarm: ${e.message}"
                )
            )
        }
    }

    /**
     * Generic executor for device control tools (direct intents/APIs).
     * These are synchronous and don't require accessibility service.
     */
    private suspend fun executeDeviceControlTool(
        toolName: String,
        requestId: String,
        params: JSONObject,
        action: (JSONObject) -> JSONObject
    ) {
        try {
            Log.i(TAG, "Executing device control tool: $toolName")
            val resultJson = action(params)
            Log.i(TAG, "[TOOL_RESULT] $toolName result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = toolName,
                    requestId = requestId,
                    result = resultJson
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing $toolName", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = toolName,
                    requestId = requestId,
                    error = "Failed to execute $toolName: ${e.message}"
                )
            )
        }
    }

    // Method to list available tools (useful for discovery)
    fun getAvailableTools(): List<String> {
        return listOf("agent_launch_app", "agent_whatsapp_select_chat", "agent_whatsapp_draft_message", "agent_whatsapp_send_message", "agent_sms_select_chat", "agent_sms_draft_message", "agent_sms_send_message", "agent_dismiss_draft", "agent_disable_continuous_listening", "agent_set_tts_enabled", "agent_play_youtube_music", "agent_queue_youtube_music", "agent_search_google_maps_location", "agent_search_google_maps_phrase", "agent_get_google_maps_directions", "agent_recenter_google_maps", "agent_fullscreen_google_maps", "agent_select_location_from_list", "agent_set_alarm", "agent_set_timer", "agent_dismiss_alarm", "agent_dismiss_timer", "agent_get_next_alarm", "agent_delete_alarm", "agent_dismiss_amdroid_alarm", "agent_toggle_flashlight", "agent_draft_calendar_event", "agent_save_calendar_event", "agent_dial_phone_number", "agent_set_volume", "agent_lookup_phone_contacts")
    }
    
    // Method to get tool schema (useful for the server to know what parameters are needed)
    fun getToolSchema(toolName: String): JSONObject? {
        return when (toolName) {
            "agent_launch_app" -> {
                JSONObject().apply {
                    put("name", "agent_launch_app")
                    put("description", "Launch an application by its name. The return value includes an 'available_tools' array listing specialized tools that can control the launched app (e.g., for Maps, YouTube Music, WhatsApp). Check the return value and use those tools to complete the user's request.")
                    put("parameters", JSONObject().apply {
                        put("app_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "The name of the app to launch")
                            put("required", true)
                        })
                    })
                }
            }
            "agent_whatsapp_select_chat" -> {
                JSONObject().apply {
                    put("name", "agent_whatsapp_select_chat")
                    put("description", "Select a specific chat in WhatsApp")
                    put("parameters", JSONObject().apply {
                        put("chat_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "The name of the chat/contact to select")
                            put("required", true)
                        })
                    })
                }
            }
            "agent_whatsapp_send_message" -> {
                JSONObject().apply {
                    put("name", "agent_whatsapp_send_message")
                    put("description", "Send a message in WhatsApp. MUST have already drafted the message and received user confirmation.")
                    put("parameters", JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("type", "string")
                            put("description", "The message text to send")
                            put("required", true)
                        })
                    })
                }
            }
            "agent_whatsapp_draft_message" -> {
                JSONObject().apply {
                    put("name", "agent_whatsapp_draft_message")
                    put("description", "Draft a message in WhatsApp. Shows overlay for user review. ALWAYS use this before sending to let user confirm the message text.")
                    put("parameters", JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("type", "string")
                            put("description", "The message text to draft for user review")
                            put("required", true)
                        })
                    })
                }
            }
            "agent_sms_select_chat" -> {
                JSONObject().apply {
                    put("name", "agent_sms_select_chat")
                    put("description", "Select a specific contact/conversation in Google Messages app")
                    put("parameters", JSONObject().apply {
                        put("contact_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "The name or phone number of the contact to select")
                            put("required", true)
                        })
                    })
                }
            }
            "agent_sms_draft_message" -> {
                JSONObject().apply {
                    put("name", "agent_sms_draft_message")
                    put("description", "Draft an SMS/text message in Google Messages. Shows overlay for user review. ALWAYS use this before sending to let user confirm the message text.")
                    put("parameters", JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("type", "string")
                            put("description", "The SMS/text message text to draft for user review")
                            put("required", true)
                        })
                    })
                }
            }
            "agent_sms_send_message" -> {
                JSONObject().apply {
                    put("name", "agent_sms_send_message")
                    put("description", "Send an SMS/text message in Google Messages. MUST have already drafted the message and received user confirmation.")
                    put("parameters", JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("type", "string")
                            put("description", "The SMS/text message text to send")
                            put("required", true)
                        })
                    })
                }
            }
            else -> null
        }
    }
}