package com.example.whiz.tools

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.whiz.accessibility.WhizAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhatsAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLauncherTool: AppLauncherTool
) {
    private val TAG = "WhatsAppTool"
    
    data class WhatsAppResult(
        val success: Boolean,
        val action: String,
        val chatName: String? = null,
        val error: String? = null
    )
    
    suspend fun selectChat(chatName: String): WhatsAppResult {
        Log.d(TAG, "Attempting to select WhatsApp chat: $chatName")
        
        try {
            // First, launch WhatsApp if it's not already open
            val launchResult = appLauncherTool.launchApp("WhatsApp", enableOverlay = false)
            if (!launchResult.success) {
                return WhatsAppResult(
                    success = false,
                    action = "select_chat",
                    chatName = chatName,
                    error = "Failed to launch WhatsApp: ${launchResult.error}"
                )
            }
            
            // Wait for WhatsApp to open
            delay(2000)
            
            // Get accessibility service instance
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                Log.e(TAG, "Accessibility service not available")
                return WhatsAppResult(
                    success = false,
                    action = "select_chat",
                    chatName = chatName,
                    error = "Accessibility service not enabled. Please enable it in settings."
                )
            }
            
            // Try to find and click on the chat
            var success = false
            var attempts = 0
            val maxAttempts = 3
            
            while (!success && attempts < maxAttempts) {
                attempts++
                Log.d(TAG, "Attempt $attempts to find chat: $chatName")
                
                // Get the root node
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode == null) {
                    Log.w(TAG, "Could not get root node")
                    delay(1000)
                    continue
                }
                
                // Search for the chat name in the UI
                val chatNodes = findChatNodes(rootNode, chatName)
                if (chatNodes.isNotEmpty()) {
                    Log.d(TAG, "Found ${chatNodes.size} nodes matching chat name")
                    
                    // Click on the first matching node
                    val clickableNode = findClickableParent(chatNodes[0])
                    if (clickableNode != null) {
                        success = accessibilityService.clickNode(clickableNode)
                        Log.d(TAG, "Click result: $success")
                        
                        if (success) {
                            // Recycle nodes to prevent memory leaks
                            chatNodes.forEach { it.recycle() }
                            rootNode.recycle()
                            
                            return WhatsAppResult(
                                success = true,
                                action = "select_chat",
                                chatName = chatName
                            )
                        }
                    } else {
                        Log.w(TAG, "No clickable parent found for chat node")
                    }
                    
                    // Recycle nodes
                    chatNodes.forEach { it.recycle() }
                }
                
                // Recycle root node
                rootNode.recycle()
                
                // Wait before retrying
                delay(1000)
            }
            
            Log.w(TAG, "Could not find or click on chat: $chatName after $attempts attempts")
            return WhatsAppResult(
                success = false,
                action = "select_chat",
                chatName = chatName,
                error = "Could not find chat named '$chatName' in WhatsApp"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting WhatsApp chat", e)
            return WhatsAppResult(
                success = false,
                action = "select_chat",
                chatName = chatName,
                error = "Error selecting chat: ${e.message}"
            )
        }
    }
    
    private fun findChatNodes(node: AccessibilityNodeInfo, chatName: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val normalizedChatName = chatName.lowercase().trim()
        
        // Search by text
        val nodesByText = node.findAccessibilityNodeInfosByText(chatName)
        if (nodesByText != null) {
            results.addAll(nodesByText)
        }
        
        // Also do a recursive search for partial matches
        searchNodeRecursively(node, normalizedChatName, results)
        
        return results
    }
    
    private fun searchNodeRecursively(
        node: AccessibilityNodeInfo, 
        searchText: String, 
        results: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            // Check current node's text
            val nodeText = node.text?.toString()?.lowercase()
            val contentDesc = node.contentDescription?.toString()?.lowercase()
            
            if ((nodeText != null && nodeText.contains(searchText)) ||
                (contentDesc != null && contentDesc.contains(searchText))) {
                results.add(AccessibilityNodeInfo.obtain(node))
            }
            
            // Search children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    searchNodeRecursively(child, searchText, results)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching node recursively", e)
        }
    }
    
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            
            val parent = current.parent
            current.recycle()
            current = parent
        }
        
        return null
    }
    
    suspend fun sendMessage(message: String): WhatsAppResult {
        Log.d(TAG, "Attempting to send message in WhatsApp: $message")
        
        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                return WhatsAppResult(
                    success = false,
                    action = "send_message",
                    error = "Accessibility service not enabled"
                )
            }
            
            // Wait a bit to ensure we're in a chat
            delay(1000)
            
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return WhatsAppResult(
                    success = false,
                    action = "send_message",
                    error = "Could not get root node"
                )
            }
            
            // Find the message input field (usually has hint text "Message" or "Type a message")
            val inputNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditTextNodes(rootNode, inputNodes)
            
            if (inputNodes.isNotEmpty()) {
                val inputNode = inputNodes[0]
                
                // Set the text
                val bundle = android.os.Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    message
                )
                val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                
                if (textSet) {
                    Log.d(TAG, "Message text set successfully")
                    
                    // Find and click send button
                    delay(500)
                    val sendSuccess = clickSendButton(rootNode)
                    
                    // Clean up
                    inputNodes.forEach { it.recycle() }
                    rootNode.recycle()
                    
                    return if (sendSuccess) {
                        WhatsAppResult(
                            success = true,
                            action = "send_message"
                        )
                    } else {
                        WhatsAppResult(
                            success = false,
                            action = "send_message",
                            error = "Could not find or click send button"
                        )
                    }
                }
            }
            
            // Clean up
            inputNodes.forEach { it.recycle() }
            rootNode.recycle()
            
            return WhatsAppResult(
                success = false,
                action = "send_message",
                error = "Could not find message input field"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending WhatsApp message", e)
            return WhatsAppResult(
                success = false,
                action = "send_message",
                error = "Error sending message: ${e.message}"
            )
        }
    }
    
    private fun findEditTextNodes(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        try {
            if (node.className == "android.widget.EditText") {
                results.add(AccessibilityNodeInfo.obtain(node))
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findEditTextNodes(child, results)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding EditText nodes", e)
        }
    }
    
    private fun clickSendButton(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Look for send button by content description
            val sendDescriptions = listOf("Send", "send", "Send button")
            
            for (desc in sendDescriptions) {
                val sendNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                if (sendNodes != null && sendNodes.isNotEmpty()) {
                    val success = sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    sendNodes.forEach { it.recycle() }
                    if (success) return true
                }
            }
            
            // Try to find by traversing the tree
            return findAndClickSendButton(rootNode)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking send button", e)
            return false
        }
    }
    
    private fun findAndClickSendButton(node: AccessibilityNodeInfo): Boolean {
        try {
            // Check if this node is a send button
            val contentDesc = node.contentDescription?.toString()?.lowercase()
            if (contentDesc != null && contentDesc.contains("send") && node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    if (findAndClickSendButton(child)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in findAndClickSendButton", e)
        }
        
        return false
    }
}