package com.example.whiz.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Shared utility for dumping accessibility node trees.
 * Used by both WhizAccessibilityService (on-demand dumps) and ScreenAgentTools (error dumps).
 */
object AccessibilityDumpUtil {

    fun dumpNodeRecursive(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val resourceId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        sb.appendLine("$indent[$className] id=$resourceId text=\"$text\" desc=\"$contentDesc\" bounds=$bounds clickable=${node.isClickable}")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                dumpNodeRecursive(child, sb, depth + 1)
            }
        }
    }
}
