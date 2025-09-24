package com.example.whiz.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only BroadcastReceiver to query authentication state via ADB.
 *
 * Usage:
 * adb shell am broadcast -a com.example.whiz.debug.CHECK_AUTH_STATE -p com.example.whiz.debug
 *
 * Returns via logcat:
 * - AUTH_STATE: LOGGED_IN or NOT_LOGGED_IN
 * - User email if logged in
 */
@AndroidEntryPoint
class AuthStateReceiver : BroadcastReceiver() {

    @Inject
    lateinit var authRepository: AuthRepository

    companion object {
        private const val TAG = "AuthStateReceiver"
        const val ACTION_CHECK_AUTH = "com.example.whiz.debug.CHECK_AUTH_STATE"
        const val ACTION_FORCE_LOGOUT = "com.example.whiz.debug.FORCE_LOGOUT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CHECK_AUTH -> {
                checkAuthState()
            }
            ACTION_FORCE_LOGOUT -> {
                forceLogout()
            }
        }
    }

    private fun checkAuthState() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Check if user is signed in
                val isSignedIn = authRepository.isSignedIn()
                val userProfile = authRepository.userProfile.value

                if (isSignedIn && userProfile != null) {
                    Log.i(TAG, "AUTH_STATE: LOGGED_IN")
                    Log.i(TAG, "AUTH_USER: ${userProfile.email}")
                    Log.i(TAG, "AUTH_NAME: ${userProfile.name}")
                } else {
                    Log.i(TAG, "AUTH_STATE: NOT_LOGGED_IN")
                }

                // Also set result code that can be checked by shell
                setResultCode(if (isSignedIn) 1 else 0)

            } catch (e: Exception) {
                Log.e(TAG, "AUTH_STATE: ERROR - ${e.message}")
                setResultCode(-1)
            }
        }
    }

    private fun forceLogout() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                authRepository.signOut()
                Log.i(TAG, "AUTH_STATE: LOGGED_OUT_SUCCESSFULLY")
                setResultCode(1)
            } catch (e: Exception) {
                Log.e(TAG, "AUTH_STATE: LOGOUT_FAILED - ${e.message}")
                setResultCode(-1)
            }
        }
    }
}