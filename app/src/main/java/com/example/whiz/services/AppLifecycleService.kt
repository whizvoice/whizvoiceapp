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
    
    // Track current foreground state
    private var _isInForeground = true // Assume foreground initially
    
    // App foreground events - replay=1 so tests can catch events emitted before collection starts
    private val _appForegroundEvent = MutableSharedFlow<Unit>(replay = 1)
    val appForegroundEvent: SharedFlow<Unit> = _appForegroundEvent.asSharedFlow()
    
    // App background events - replay=1 so tests can catch events emitted before collection starts
    private val _appBackgroundEvent = MutableSharedFlow<Unit>(replay = 1)
    val appBackgroundEvent: SharedFlow<Unit> = _appBackgroundEvent.asSharedFlow()
    
    fun notifyAppForegrounded() {
        _isInForeground = true
        _appForegroundEvent.tryEmit(Unit)
    }
    
    fun notifyAppBackgrounded() {
        _isInForeground = false
        _appBackgroundEvent.tryEmit(Unit)
    }
    
    fun isInForeground(): Boolean {
        return _isInForeground
    }
} 