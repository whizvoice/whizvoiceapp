package com.example.whiz.tools

import android.content.Context
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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
}

@Singleton
class ToolExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenAgentTools: ScreenAgentTools,
    private val userPreferences: com.example.whiz.data.preferences.UserPreferences,
    private val authRepository: com.example.whiz.data.auth.AuthRepository
) {
    private val TAG = "ToolExecutor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
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

                when (toolName) {
                    "launch_app" -> {
                        Log.i(TAG, "🎯 Matched launch_app tool, calling executeAppLauncher")
                        executeAppLauncher(requestId, params)
                    }
                    "whatsapp_select_chat" -> {
                        executeWhatsAppSelectChat(requestId, params)
                    }
                    "whatsapp_send_message" -> {
                        executeWhatsAppSendMessage(requestId, params)
                    }
                    "whatsapp_draft_message" -> {
                        executeWhatsAppDraftMessage(requestId, params)
                    }
                    "sms_select_chat" -> {
                        executeSMSSelectChat(requestId, params)
                    }
                    "sms_draft_message" -> {
                        executeSMSDraftMessage(requestId, params)
                    }
                    "sms_send_message" -> {
                        executeSMSSendMessage(requestId, params)
                    }
                    "disable_continuous_listening" -> {
                        executeDisableContinuousListening(requestId, voiceManager)
                    }
                    "set_tts_enabled" -> {
                        executeSetTTSEnabled(requestId, params, chatViewModel)
                    }
                    "play_youtube_music" -> {
                        executePlayYouTubeMusic(requestId, params)
                    }
                    "queue_youtube_music" -> {
                        executeQueueYouTubeMusic(requestId, params)
                    }
                    "search_google_maps_location" -> {
                        executeSearchGoogleMapsLocation(requestId, params)
                    }
                    "search_google_maps_phrase" -> {
                        executeSearchGoogleMapsPhrase(requestId, params)
                    }
                    "get_google_maps_directions" -> {
                        executeGetGoogleMapsDirections(requestId, params)
                    }
                    "recenter_google_maps" -> {
                        executeRecenterGoogleMaps(requestId, params)
                    }
                    "select_location_from_list" -> {
                        executeSelectLocationFromList(requestId, params)
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
                    put("name", "search_google_maps_location")
                    put("description", "Search for a specific address or place name in Google Maps and show results. After successfully searching, the tool returns the exact address of the selected location - ALWAYS read this address back to the user to confirm the correct place was found.")
                },
                JSONObject().apply {
                    put("name", "search_google_maps_phrase")
                    put("description", "Search for places by category/phrase (e.g., 'coffee shops nearby', 'gas stations') in Google Maps")
                },
                JSONObject().apply {
                    put("name", "get_google_maps_directions")
                    put("description", "Get directions to the currently selected location. Must call search first.")
                },
                JSONObject().apply {
                    put("name", "select_location_from_list")
                    put("description", "Select a specific location from search results by position or name fragment. After successfully selecting, the tool returns the exact address of the selected location - ALWAYS read this address back to the user to confirm the correct place was selected.")
                },
                JSONObject().apply {
                    put("name", "recenter_google_maps")
                    put("description", "Re-center the map to your current location")
                }
            )
            "com.google.android.apps.youtube.music" -> listOf(
                JSONObject().apply {
                    put("name", "play_youtube_music")
                    put("description", "Play a song or artist in YouTube Music")
                },
                JSONObject().apply {
                    put("name", "queue_youtube_music")
                    put("description", "Add a song or artist to the YouTube Music queue")
                }
            )
            "com.whatsapp" -> listOf(
                JSONObject().apply {
                    put("name", "whatsapp_select_chat")
                    put("description", "Select a specific chat in WhatsApp by contact/group name")
                },
                JSONObject().apply {
                    put("name", "whatsapp_draft_message")
                    put("description", "Draft a message for user review before sending")
                },
                JSONObject().apply {
                    put("name", "whatsapp_send_message")
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
                            put("name", "sms_select_chat")
                            put("description", "Select a specific SMS conversation by contact name")
                        },
                        JSONObject().apply {
                            put("name", "sms_draft_message")
                            put("description", "Draft an SMS message for user review before sending")
                        },
                        JSONObject().apply {
                            put("name", "sms_send_message")
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
                    toolName = "launch_app",
                    requestId = requestId,
                    result = resultJson
                )
            )

            Log.i(TAG, "📤 [TOOL_RESULT] Successfully emitted tool result for requestId=$requestId")

        } catch (e: Exception) {
            Log.e(TAG, "Error executing app launcher", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "launch_app",
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
                    toolName = "whatsapp_select_chat",
                    requestId = requestId,
                    result = resultJson
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp select chat", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "whatsapp_select_chat",
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
            Log.i(TAG, "Drafting WhatsApp message: message='$message', previousText='$previousText'")
            
            val result = screenAgentTools.draftWhatsAppMessage(message, previousText)
            
            val resultJson = JSONObject().apply {
                put("success", result.success)
                result.message?.let { put("message", it) }
                result.error?.let { put("error", it) }
                put("overlay_shown", result.overlayShown)
            }
            
            Log.i(TAG, "[TOOL_RESULT] WhatsApp draft message result for requestId=$requestId: ${resultJson.toString(2)}")
            
            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "whatsapp_draft_message",
                    requestId = requestId,
                    result = resultJson
                )
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp draft message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "whatsapp_draft_message",
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
                    toolName = "whatsapp_send_message",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp send message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "whatsapp_send_message",
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
                    toolName = "sms_select_chat",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing SMS select chat", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "sms_select_chat",
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

            Log.i(TAG, "Drafting SMS message: message='$message', previousText='$previousText'")

            val result = screenAgentTools.draftSMSMessage(message, previousText)

            val resultJson = JSONObject().apply {
                put("success", result.success)
                result.message?.let { put("message", it) }
                result.error?.let { put("error", it) }
                put("overlay_shown", result.overlayShown)
            }

            Log.i(TAG, "[TOOL_RESULT] SMS draft message result for requestId=$requestId: ${resultJson.toString(2)}")

            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "sms_draft_message",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing SMS draft message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "sms_draft_message",
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
                    toolName = "sms_send_message",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing SMS send message", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "sms_send_message",
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
                        toolName = "disable_continuous_listening",
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
                    toolName = "disable_continuous_listening",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error disabling continuous listening", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "disable_continuous_listening",
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
                        toolName = "set_tts_enabled",
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

            if (isBubbleActive) {
                // In bubble mode, switch modes based on TTS enabled state
                if (enabled) {
                    Log.i(TAG, "Bubble mode active - switching to TTS_WITH_LISTENING mode")
                    com.example.whiz.services.BubbleOverlayService.setMode(com.example.whiz.services.ListeningMode.TTS_WITH_LISTENING)
                } else {
                    Log.i(TAG, "Bubble mode active - switching to CONTINUOUS_LISTENING mode")
                    com.example.whiz.services.BubbleOverlayService.setMode(com.example.whiz.services.ListeningMode.CONTINUOUS_LISTENING)
                }
            } else {
                // Not in bubble mode, use normal chatViewModel
                chatViewModel.setVoiceResponseEnabled(enabled)
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
                    toolName = "set_tts_enabled",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error setting TTS enabled", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "set_tts_enabled",
                    requestId = requestId,
                    error = "Failed to set TTS enabled: ${e.message}"
                )
            )
        }
    }
    
    private suspend fun executePlayYouTubeMusic(requestId: String, params: JSONObject) {
        try {
            val query = params.getString("query")
            Log.i(TAG, "Playing song on YouTube Music: $query")

            // The server has already decided to use YouTube Music by calling this tool
            // No need to check preferences here - just execute the action
            val result = screenAgentTools.playYouTubeMusicSong(query)

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
                    toolName = "play_youtube_music",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing music play", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "play_youtube_music",
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
                    toolName = "queue_youtube_music",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing music queue", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "queue_youtube_music",
                    requestId = requestId,
                    error = "Failed to queue song: ${e.message}"
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
                    toolName = "search_google_maps_location",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps search", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "search_google_maps_location",
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
                    toolName = "search_google_maps_phrase",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps phrase search", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "search_google_maps_phrase",
                    requestId = requestId,
                    error = "Failed to search phrase: ${e.message}"
                )
            )
        }
    }

    private suspend fun executeGetGoogleMapsDirections(requestId: String, params: JSONObject) {
        try {
            val mode = if (params.has("mode")) params.getString("mode") else null
            val alreadyInDirections = if (params.has("already_in_directions")) params.getBoolean("already_in_directions") else false
            Log.i(TAG, "Getting Google Maps directions with mode: ${mode ?: "default"}, alreadyInDirections: $alreadyInDirections")

            val result = screenAgentTools.getGoogleMapsDirections(mode, alreadyInDirections)

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
                    toolName = "get_google_maps_directions",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps directions", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "get_google_maps_directions",
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
                    toolName = "recenter_google_maps",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing Google Maps recenter", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "recenter_google_maps",
                    requestId = requestId,
                    error = "Failed to recenter map: ${e.message}"
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
                    toolName = "select_location_from_list",
                    requestId = requestId,
                    result = resultJson
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error executing select location from list", e)
            _toolResults.emit(
                ToolExecutionResult.Error(
                    toolName = "select_location_from_list",
                    requestId = requestId,
                    error = "Failed to select location: ${e.message}"
                )
            )
        }
    }

    // Method to list available tools (useful for discovery)
    fun getAvailableTools(): List<String> {
        return listOf("launch_app", "whatsapp_select_chat", "whatsapp_draft_message", "whatsapp_send_message", "sms_select_chat", "sms_draft_message", "sms_send_message", "disable_continuous_listening", "set_tts_enabled", "play_youtube_music", "queue_youtube_music", "search_google_maps_location", "search_google_maps_phrase", "get_google_maps_directions", "recenter_google_maps", "select_location_from_list")
    }
    
    // Method to get tool schema (useful for the server to know what parameters are needed)
    fun getToolSchema(toolName: String): JSONObject? {
        return when (toolName) {
            "launch_app" -> {
                JSONObject().apply {
                    put("name", "launch_app")
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
            "whatsapp_select_chat" -> {
                JSONObject().apply {
                    put("name", "whatsapp_select_chat")
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
            "whatsapp_send_message" -> {
                JSONObject().apply {
                    put("name", "whatsapp_send_message")
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
            "whatsapp_draft_message" -> {
                JSONObject().apply {
                    put("name", "whatsapp_draft_message")
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
            "sms_select_chat" -> {
                JSONObject().apply {
                    put("name", "sms_select_chat")
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
            "sms_draft_message" -> {
                JSONObject().apply {
                    put("name", "sms_draft_message")
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
            "sms_send_message" -> {
                JSONObject().apply {
                    put("name", "sms_send_message")
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