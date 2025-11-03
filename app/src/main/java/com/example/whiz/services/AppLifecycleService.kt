package com.example.whiz.services

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLifecycleService @Inject constructor() : DefaultLifecycleObserver {

    private val TAG = "AppLifecycleService"

    // Track current foreground state
    private var _isInForeground = false // Start as false, will be set by lifecycle

    // App foreground events - replay=1 so tests can catch events emitted before collection starts
    private val _appForegroundEvent = MutableSharedFlow<Unit>(replay = 1)
    val appForegroundEvent: SharedFlow<Unit> = _appForegroundEvent.asSharedFlow()

    // App background events - replay=1 so tests can catch events emitted before collection starts
    private val _appBackgroundEvent = MutableSharedFlow<Unit>(replay = 1)
    val appBackgroundEvent: SharedFlow<Unit> = _appBackgroundEvent.asSharedFlow()

    // Track whether we've already registered the observer
    @Volatile
    private var isObserverRegistered = false

    init {
        // Register observer on main thread to avoid IllegalStateException in tests
        // where Hilt instantiates this singleton on a background thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread, register immediately
            registerLifecycleObserver()
        } else {
            // Not on main thread (e.g., during test setup), post to main thread
            Handler(Looper.getMainLooper()).post {
                registerLifecycleObserver()
            }
        }
    }

    private fun registerLifecycleObserver() {
        if (!isObserverRegistered) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            isObserverRegistered = true
            Log.d(TAG, "AppLifecycleService initialized with ProcessLifecycleOwner")
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        // App moved to foreground (at least one activity is visible)
        Log.d(TAG, "App foregrounded (onStart)")
        _isInForeground = true
        _appForegroundEvent.tryEmit(Unit)
    }

    override fun onStop(owner: LifecycleOwner) {
        // App moved to background (no activities are visible)
        Log.d(TAG, "App backgrounded (onStop)")
        _isInForeground = false
        _appBackgroundEvent.tryEmit(Unit)
    }

    fun isInForeground(): Boolean {
        return _isInForeground
    }

    // Test helpers to manually trigger lifecycle events
    fun notifyAppForegrounded() {
        Log.d(TAG, "Manual notification: App foregrounded")
        _isInForeground = true
        _appForegroundEvent.tryEmit(Unit)
    }

    fun notifyAppBackgrounded() {
        Log.d(TAG, "Manual notification: App backgrounded")
        _isInForeground = false
        _appBackgroundEvent.tryEmit(Unit)
    }
} 