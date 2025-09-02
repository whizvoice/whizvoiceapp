package com.example.whiz

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed

/**
 * Helper class for Compose UI tests
 */
class ComposeTestHelper(
    private val composeTestRule: ComposeTestRule
) {
    /**
     * Wait for text to appear on screen with timeout
     */
    fun waitForText(text: String, timeout: Long = 3000): Boolean {
        return try {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeout) {
                try {
                    composeTestRule
                        .onNodeWithText(text)
                        .assertIsDisplayed()
                    return true
                } catch (e: AssertionError) {
                    Thread.sleep(100)
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}