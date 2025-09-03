package com.example.whiz.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.example.whiz.MainActivity
import com.example.whiz.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs

class BubbleOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var expandedView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var recognitionJob: Job? = null
    private var botResponseJob: Job? = null
    
    companion object {
        private const val TAG = "BubbleOverlayService"
        private const val CLICK_THRESHOLD = 10
        
        // Flows for displaying text in bubble
        private val _botResponseFlow = MutableSharedFlow<String>(replay = 1)
        val botResponseFlow: SharedFlow<String> = _botResponseFlow
        
        private val _userTranscriptionFlow = MutableSharedFlow<String>(replay = 1)
        val userTranscriptionFlow: SharedFlow<String> = _userTranscriptionFlow
        
        fun start(context: Context) {
            val intent = Intent(context, BubbleOverlayService::class.java)
            context.startService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, BubbleOverlayService::class.java)
            context.stopService(intent)
        }
        
        fun updateBotResponse(text: String) {
            GlobalScope.launch {
                _botResponseFlow.emit(text)
            }
        }
        
        fun updateUserTranscription(text: String) {
            GlobalScope.launch {
                _userTranscriptionFlow.emit(text)
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createBubbleView()
        createExpandedView()
        startVoiceTranscriptionListener()
        startBotResponseListener()
    }
    
    @SuppressLint("InflateParams")
    private fun createBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_overlay, null)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 200
        }
        
        setupBubbleTouchListener(params)
        
        try {
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
        }
    }
    
    @SuppressLint("InflateParams")
    private fun createExpandedView() {
        expandedView = LayoutInflater.from(this).inflate(R.layout.bubble_expanded, null)
        expandedView?.visibility = View.GONE
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        
        expandedView?.findViewById<ImageView>(R.id.collapse_button)?.setOnClickListener {
            collapseView()
        }
        
        expandedView?.findViewById<View>(R.id.return_to_app_button)?.setOnClickListener {
            returnToApp()
        }
        
        expandedView?.findViewById<View>(R.id.stop_button)?.setOnClickListener {
            stopSelf()
        }
        
        try {
            windowManager.addView(expandedView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add expanded view", e)
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L
        
        bubbleView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val clickDuration = System.currentTimeMillis() - touchStartTime
                    val xDiff = abs(event.rawX - initialTouchX)
                    val yDiff = abs(event.rawY - initialTouchY)
                    
                    if (clickDuration < 200 && xDiff < CLICK_THRESHOLD && yDiff < CLICK_THRESHOLD) {
                        onBubbleClick()
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun onBubbleClick() {
        val isExpanded = expandedView?.visibility == View.VISIBLE
        if (isExpanded) {
            collapseView()
        } else {
            expandView()
        }
    }
    
    private fun expandView() {
        bubbleView?.visibility = View.GONE
        expandedView?.visibility = View.VISIBLE
    }
    
    private fun collapseView() {
        expandedView?.visibility = View.GONE
        bubbleView?.visibility = View.VISIBLE
    }
    
    private fun returnToApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        stopSelf()
    }
    
    private fun startVoiceTranscriptionListener() {
        recognitionJob = serviceScope.launch {
            userTranscriptionFlow
                .distinctUntilChanged()
                .collect { transcription ->
                    updateDisplayText(transcription, isUserInput = true)
                }
        }
    }
    
    private fun startBotResponseListener() {
        botResponseJob = serviceScope.launch {
            botResponseFlow
                .filterNotNull()
                .distinctUntilChanged()
                .collect { response ->
                    // Filter out JSON responses (tool calls)
                    if (!response.startsWith("{") && !response.startsWith("[")) {
                        updateDisplayText(response, isUserInput = false)
                        
                        // Clear bot response after 5 seconds
                        delay(5000)
                        if (expandedView?.findViewById<TextView>(R.id.expanded_transcription_text)?.text == response) {
                            updateDisplayText("", isUserInput = false)
                        }
                    }
                }
        }
    }
    
    private fun updateDisplayText(text: String, isUserInput: Boolean) {
        val displayText = if (text.isNotEmpty()) {
            if (isUserInput) "🎤 $text" else "🤖 $text"
        } else {
            ""
        }
        
        bubbleView?.findViewById<TextView>(R.id.transcription_text)?.apply {
            this.text = displayText
            visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        }
        
        expandedView?.findViewById<TextView>(R.id.expanded_transcription_text)?.apply {
            this.text = displayText
        }
        
        bubbleView?.findViewById<CardView>(R.id.bubble_card)?.apply {
            val backgroundRes = if (text.isNotEmpty()) {
                R.drawable.bubble_background_active
            } else {
                R.drawable.bubble_background
            }
            setBackgroundResource(backgroundRes)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        recognitionJob?.cancel()
        botResponseJob?.cancel()
        try {
            bubbleView?.let { windowManager.removeView(it) }
            expandedView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing views", e)
        }
    }
}