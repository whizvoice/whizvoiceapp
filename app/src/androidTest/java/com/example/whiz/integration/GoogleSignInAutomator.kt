package com.example.whiz.integration

import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.By
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared GoogleSignInAutomator for all tests to avoid redeclaration errors.
 * This provides a single, reliable implementation for Google Sign-In automation.
 */
object GoogleSignInAutomator {
    
    private fun getTimestamp(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
    }
    
    private fun logWithTimestamp(tag: String, message: String) {
        Log.d(tag, "[${getTimestamp()}] $message")
    }
    
    fun performGoogleSignIn(device: UiDevice, testEmail: String, testPassword: String): Boolean {
        val TAG = "GoogleSignInAutomator"
        
        try {
            logWithTimestamp(TAG, "🚀 Starting Google Sign-In automation for: $testEmail")
            
            // Wait for Google sign-in screen
            logWithTimestamp(TAG, "⏳ Waiting for Google sign-in screen...")
            
            // Look for existing account button first
            val accountButton = device.wait(Until.findObject(
                By.text(testEmail).pkg("com.google.android.gms")
            ), 10000) ?: device.wait(Until.findObject(
                By.textContains(testEmail).pkg("com.google.android.gms")
            ), 5000)
            
            if (accountButton != null) {
                logWithTimestamp(TAG, "✅ Found existing account button, clicking...")
                accountButton.click()
                Thread.sleep(2000)
                logWithTimestamp(TAG, "🎉 Successfully used existing account!")
                return true
            }
            
            // If account not found, try manual sign-in
            logWithTimestamp(TAG, "📝 Account not found, attempting manual sign-in...")
            
            val emailField = device.wait(Until.findObject(
                By.res("identifierId")
            ), 5000)
            
            if (emailField != null) {
                logWithTimestamp(TAG, "📧 Found email field, entering email...")
                emailField.text = testEmail
                Thread.sleep(1000)
                
                val nextButton = device.findObject(By.text("Next"))
                if (nextButton != null) {
                    logWithTimestamp(TAG, "⏭️ Clicking Next button...")
                    nextButton.click()
                    Thread.sleep(2000)
                }
                
                // Wait for password field
                val passwordField = device.wait(Until.findObject(
                    By.clazz("android.widget.EditText")
                ), 5000)
                
                if (passwordField != null) {
                    logWithTimestamp(TAG, "🔑 Found password field, entering password...")
                    passwordField.text = testPassword
                    Thread.sleep(1000)
                    
                    val signInButton = device.findObject(By.text("Next")) ?: 
                                     device.findObject(By.text("Sign in"))
                    
                    if (signInButton != null) {
                        logWithTimestamp(TAG, "🔐 Clicking sign-in button...")
                        signInButton.click()
                        Thread.sleep(3000)
                        logWithTimestamp(TAG, "🎉 Manual sign-in completed!")
                        return true
                    } else {
                        logWithTimestamp(TAG, "❌ Could not find sign-in button")
                    }
                } else {
                    logWithTimestamp(TAG, "❌ Could not find password field")
                }
            } else {
                logWithTimestamp(TAG, "❌ Could not find email field")
            }
            
            logWithTimestamp(TAG, "❌ Google Sign-In automation failed")
            return false
            
        } catch (e: Exception) {
            logWithTimestamp(TAG, "💥 Exception during Google Sign-In: ${e.message}")
            return false
        }
    }
} 