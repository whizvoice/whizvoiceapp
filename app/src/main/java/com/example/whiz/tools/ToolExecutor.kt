package com.example.whiz.tools

import android.util.Log
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
    private val screenAgentTools: ScreenAgentTools
) {
    private val TAG = "ToolExecutor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private val _toolResults = MutableSharedFlow<ToolExecutionResult>()
    val toolResults: SharedFlow<ToolExecutionResult> = _toolResults.asSharedFlow()
    
    fun executeToolFromJson(toolRequest: JSONObject) {
        scope.launch {
            try {
                val toolName = toolRequest.getString("tool")
                val requestId = toolRequest.getString("request_id")
                val params = if (toolRequest.has("params")) {
                    toolRequest.getJSONObject("params")
                } else {
                    JSONObject()
                }
                
                Log.i(TAG, "Executing tool: $toolName with requestId: $requestId")
                Log.i(TAG, "Tool params: ${params.toString(2)}")
                
                when (toolName) {
                    "launch_app" -> {
                        executeAppLauncher(requestId, params)
                    }
                    "whatsapp_select_chat" -> {
                        executeWhatsAppSelectChat(requestId, params)
                    }
                    "whatsapp_send_message" -> {
                        executeWhatsAppSendMessage(requestId, params)
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
    
    private suspend fun executeAppLauncher(requestId: String, params: JSONObject) {
        try {
            val appName = params.getString("app_name")
            Log.d(TAG, "Launching app: $appName")
            
            val result = screenAgentTools.launchApp(appName)
            
            val resultJson = JSONObject().apply {
                put("success", result.success)
                put("app_name", result.appName)
                result.packageName?.let { put("package_name", it) }
                result.error?.let { put("error", it) }
            }
            
            Log.i(TAG, "[TOOL_RESULT] About to emit tool result for requestId=$requestId")
            Log.i(TAG, "[TOOL_RESULT] Result JSON: ${resultJson.toString(2)}")
            
            _toolResults.emit(
                ToolExecutionResult.Success(
                    toolName = "launch_app",
                    requestId = requestId,
                    result = resultJson
                )
            )
            
            Log.i(TAG, "[TOOL_RESULT] Successfully emitted tool result for requestId=$requestId")
            
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
    
    // Method to list available tools (useful for discovery)
    fun getAvailableTools(): List<String> {
        return listOf("launch_app", "whatsapp_select_chat", "whatsapp_send_message")
    }
    
    // Method to get tool schema (useful for the server to know what parameters are needed)
    fun getToolSchema(toolName: String): JSONObject? {
        return when (toolName) {
            "launch_app" -> {
                JSONObject().apply {
                    put("name", "launch_app")
                    put("description", "Launch an application by its name")
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
                    put("description", "Send a message in the current WhatsApp chat")
                    put("parameters", JSONObject().apply {
                        put("message", JSONObject().apply {
                            put("type", "string")
                            put("description", "The message text to send")
                            put("required", true)
                        })
                    })
                }
            }
            else -> null
        }
    }
}