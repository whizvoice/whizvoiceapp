package com.example.whiz.services

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLifecycleService @Inject constructor() {
    
    private val TAG = "AppLifecycleService"
    
    // App foreground events
    private val _appForegroundEvent = MutableSharedFlow<Unit>(replay = 0)
    val appForegroundEvent: SharedFlow<Unit> = _appForegroundEvent.asSharedFlow()
    
    // App background events
    private val _appBackgroundEvent = MutableSharedFlow<Unit>(replay = 0)
    val appBackgroundEvent: SharedFlow<Unit> = _appBackgroundEvent.asSharedFlow()
    
    fun notifyAppForegrounded() {
        Log.d(TAG, "notifyAppForegrounded called")
        val emitted = _appForegroundEvent.tryEmit(Unit)
        Log.d(TAG, "Event emission result: $emitted, active collectors: ${_appForegroundEvent.subscriptionCount.value}")
    }
    
    fun notifyAppBackgrounded() {
        Log.d(TAG, "notifyAppBackgrounded called")
        val emitted = _appBackgroundEvent.tryEmit(Unit)
        Log.d(TAG, "Background event emission result: $emitted, active collectors: ${_appBackgroundEvent.subscriptionCount.value}")
    }
} 