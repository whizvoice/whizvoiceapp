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
    private val appLauncherTool: AppLauncherTool
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
                
                Log.d(TAG, "Executing tool: $toolName with requestId: $requestId")
                
                when (toolName) {
                    "launch_app" -> {
                        executeAppLauncher(requestId, params)
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
            
            val result = appLauncherTool.launchApp(appName)
            
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
    
    // Method to list available tools (useful for discovery)
    fun getAvailableTools(): List<String> {
        return listOf("launch_app")
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
            else -> null
        }
    }
}