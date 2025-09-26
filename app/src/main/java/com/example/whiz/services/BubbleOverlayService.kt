package com.example.whiz.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.example.whiz.MainActivity
import com.example.whiz.R
import com.example.whiz.ui.viewmodels.VoiceManager
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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// Listening modes enum outside the class for global access
enum class ListeningMode {
    CONTINUOUS_LISTENING,  // Default - mic on, no TTS
    MIC_OFF,              // Mic off
    TTS_WITH_LISTENING    // TTS enabled + continuous listening
}

@AndroidEntryPoint
class BubbleOverlayService : Service() {
    @Inject
    lateinit var appLifecycleService: AppLifecycleService

    private lateinit var windowManager: WindowManager
    private var chatHeadView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var recognitionJob: Job? = null
    private var botResponseJob: Job? = null
    private var foregroundListenerJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideMessageRunnable: Runnable? = null

    private var currentMode = ListeningMode.CONTINUOUS_LISTENING
    
    companion object {
        private const val TAG = "BubbleOverlayService"
        private const val CLICK_THRESHOLD = 10
        private const val MESSAGE_DISPLAY_DURATION = 5000L // 5 seconds
        private const val LONG_PRESS_THRESHOLD = 500L // 500ms for long press
        
        // Track if the bubble overlay is active
        @Volatile
        var isActive: Boolean = false
            private set
        
        // Track the current listening mode
        @Volatile
        var bubbleListeningMode: ListeningMode = ListeningMode.CONTINUOUS_LISTENING
        
        // Flows for displaying text in bubble
        private val _botResponseFlow = MutableSharedFlow<String>(replay = 1)
        val botResponseFlow: SharedFlow<String> = _botResponseFlow
        
        private val _userTranscriptionFlow = MutableSharedFlow<String>(replay = 1)
        val userTranscriptionFlow: SharedFlow<String> = _userTranscriptionFlow
        
        // Flow for mode change notifications
        private val _modeChangeFlow = MutableSharedFlow<ListeningMode>(replay = 1)
        val modeChangeFlow: SharedFlow<ListeningMode> = _modeChangeFlow
        
        fun start(context: Context) {
            val intent = Intent(context, BubbleOverlayService::class.java)
            context.startService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, BubbleOverlayService::class.java)
            context.stopService(intent)
        }
        
        fun updateBotResponse(text: String) {
            Log.d(TAG, "[BUBBLE_UPDATE] updateBotResponse called with text: '$text'")
            GlobalScope.launch {
                Log.d(TAG, "[BUBBLE_UPDATE] Emitting bot response to flow")
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
        Log.d(TAG, "BubbleOverlayService onCreate - setting isActive to true")
        isActive = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChatHead()
        updateModeVisual() // Set initial visual state
        applyCurrentMode() // Apply initial mode to VoiceManager
        startVoiceTranscriptionListener()
        startBotResponseListener()
        startForegroundListener()
    }
    
    @SuppressLint("InflateParams")
    private fun createChatHead() {
        chatHeadView = LayoutInflater.from(this).inflate(R.layout.bubble_chat_head, null)
        
        // Set content description for accessibility and testing
        chatHeadView?.findViewById<CardView>(R.id.chat_head)?.contentDescription = "WhizVoice Chat Bubble"
        
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
        
        setupChatHeadTouchListener(params)
        
        try {
            windowManager.addView(chatHeadView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add chat head view", e)
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupChatHeadTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L
        var isLongPressHandled = false
        
        val chatHead = chatHeadView?.findViewById<CardView>(R.id.chat_head)
        val longPressHandler = Handler(Looper.getMainLooper())
        var longPressRunnable: Runnable? = null
        
        chatHead?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    isLongPressHandled = false
                    
                    // Set up long press detection
                    longPressRunnable = Runnable {
                        val xDiff = abs(event.rawX - initialTouchX)
                        val yDiff = abs(event.rawY - initialTouchY)
                        if (xDiff < CLICK_THRESHOLD && yDiff < CLICK_THRESHOLD) {
                            isLongPressHandled = true
                            onChatHeadLongPress()
                        }
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_THRESHOLD)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val xDiff = abs(event.rawX - initialTouchX)
                    val yDiff = abs(event.rawY - initialTouchY)
                    
                    // Cancel long press if user moves too much
                    if ((xDiff > CLICK_THRESHOLD || yDiff > CLICK_THRESHOLD) && !isLongPressHandled) {
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    }
                    
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(chatHeadView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    
                    val clickDuration = System.currentTimeMillis() - touchStartTime
                    val xDiff = abs(event.rawX - initialTouchX)
                    val yDiff = abs(event.rawY - initialTouchY)
                    
                    // Only handle click if it wasn't a long press
                    if (!isLongPressHandled && clickDuration < 200 && xDiff < CLICK_THRESHOLD && yDiff < CLICK_THRESHOLD) {
                        onChatHeadClick()
                    }
                    true
                }
                else -> false
            }
        }
    }
    
    private fun onChatHeadClick() {
        // Return to the main app when chat head is clicked
        returnToApp()
    }
    
    private fun onChatHeadLongPress() {
        Log.d(TAG, "[MODE_SWITCH] Long press detected on chat head")
        Log.d(TAG, "[MODE_SWITCH] Current mode before switch: $currentMode")
        
        // Cycle through modes
        val previousMode = currentMode
        currentMode = when (currentMode) {
            ListeningMode.CONTINUOUS_LISTENING -> ListeningMode.MIC_OFF
            ListeningMode.MIC_OFF -> ListeningMode.TTS_WITH_LISTENING
            ListeningMode.TTS_WITH_LISTENING -> ListeningMode.CONTINUOUS_LISTENING
        }
        
        Log.d(TAG, "[MODE_SWITCH] Mode switched from $previousMode to $currentMode")
        Log.d(TAG, "[MODE_SWITCH] Calling updateModeVisual...")
        updateModeVisual()
        Log.d(TAG, "[MODE_SWITCH] updateModeVisual completed, calling applyCurrentMode...")
        applyCurrentMode()
        Log.d(TAG, "[MODE_SWITCH] applyCurrentMode completed")
    }
    
    private fun updateModeVisual() {
        handler.post {
            val profileImage = chatHeadView?.findViewById<ImageView>(R.id.profile_image)
            val modeIndicator = chatHeadView?.findViewById<ImageView>(R.id.mode_indicator)
            val chatHead = chatHeadView?.findViewById<CardView>(R.id.chat_head)
            
            // Clear any existing animations
            modeIndicator?.clearAnimation()
            
            when (currentMode) {
                ListeningMode.CONTINUOUS_LISTENING -> {
                    // Robot face with microphone icon
                    profileImage?.setImageResource(R.drawable.robot_no_face)
                    profileImage?.alpha = 1.0f
                    
                    // Show mic icon overlay with pulsing animation
                    modeIndicator?.visibility = View.VISIBLE
                    modeIndicator?.setImageResource(R.drawable.ic_mic)
                    modeIndicator?.setColorFilter(
                        android.graphics.Color.BLACK,
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                    modeIndicator?.contentDescription = "Microphone Active"
                    chatHead?.contentDescription = "WhizVoice Chat Bubble - Listening Mode"
                    startPulsingAnimation(modeIndicator)
                }
                ListeningMode.MIC_OFF -> {
                    // Show original robot face with face (not no_face version)
                    profileImage?.setImageResource(R.mipmap.ic_launcher_round)
                    profileImage?.alpha = 1.0f
                    
                    // Hide mode indicator
                    modeIndicator?.visibility = View.GONE
                    modeIndicator?.clearAnimation()
                    chatHead?.contentDescription = "WhizVoice Chat Bubble - Mic Off"
                }
                ListeningMode.TTS_WITH_LISTENING -> {
                    // Robot face with speaker icon
                    profileImage?.setImageResource(R.drawable.robot_no_face)
                    profileImage?.alpha = 1.0f
                    
                    // Show speaker icon overlay with pulsing animation
                    modeIndicator?.visibility = View.VISIBLE
                    modeIndicator?.setImageResource(R.drawable.ic_volume_up)
                    modeIndicator?.setColorFilter(
                        android.graphics.Color.BLACK,
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                    modeIndicator?.contentDescription = "Speaker Active"
                    chatHead?.contentDescription = "WhizVoice Chat Bubble - Speaking Mode"
                    startPulsingAnimation(modeIndicator)
                }
            }
            
            // Show a temporary message indicating the mode change
            showModeChangeMessage()
        }
    }
    
    private fun startPulsingAnimation(view: ImageView?) {
        view?.let {
            val pulseAnimation = AlphaAnimation(0.4f, 1.0f).apply {
                duration = 800 // 0.8 seconds for smoother pulse
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            it.startAnimation(pulseAnimation)
        }
    }
    
    private fun showModeChangeMessage() {
        val modeText = when (currentMode) {
            ListeningMode.CONTINUOUS_LISTENING -> "Listening Mode"
            ListeningMode.MIC_OFF -> "Mic Off"
            ListeningMode.TTS_WITH_LISTENING -> "Speaking Mode"
        }
        
        showMessage(modeText, isUserMessage = false)
    }
    
    private fun applyCurrentMode() {
        Log.d(TAG, "[APPLY_MODE] Starting applyCurrentMode for mode: $currentMode")
        
        // Store the current mode in companion object for access from VoiceManager
        bubbleListeningMode = currentMode
        Log.d(TAG, "[APPLY_MODE] Set bubbleListeningMode to: $currentMode")
        
        // Get VoiceManager instance if available
        val voiceManager = VoiceManager.instance
        Log.d(TAG, "[APPLY_MODE] VoiceManager.instance is ${if (voiceManager != null) "available" else "NULL"}")
        
        if (voiceManager != null) {
            Log.d(TAG, "[APPLY_MODE] Directly controlling VoiceManager for mode: $currentMode")
            
            when (currentMode) {
                ListeningMode.CONTINUOUS_LISTENING -> {
                    // Enable continuous listening, disable TTS
                    Log.d(TAG, "[APPLY_MODE] Enabling continuous listening (mic on, TTS off)")
                    voiceManager.updateContinuousListeningEnabled(true)
                    Log.d(TAG, "[APPLY_MODE] Called updateContinuousListeningEnabled(true)")
                    // TTS is automatically disabled when not in TTS_WITH_LISTENING mode
                }
                ListeningMode.MIC_OFF -> {
                    // Disable continuous listening, disable TTS
                    Log.d(TAG, "[APPLY_MODE] Disabling continuous listening (mic off, TTS off)")
                    voiceManager.updateContinuousListeningEnabled(false)
                    Log.d(TAG, "[APPLY_MODE] Called updateContinuousListeningEnabled(false)")
                    // TTS is automatically disabled when not in TTS_WITH_LISTENING mode
                }
                ListeningMode.TTS_WITH_LISTENING -> {
                    // Enable continuous listening, enable TTS
                    Log.d(TAG, "[APPLY_MODE] Enabling continuous listening with TTS (mic on, TTS on)")
                    voiceManager.updateContinuousListeningEnabled(true)
                    Log.d(TAG, "[APPLY_MODE] Called updateContinuousListeningEnabled(true) for TTS mode")
                    // TTS will be enabled through shouldEnableTTS() check
                }
            }
        } else {
            Log.w(TAG, "[APPLY_MODE] VoiceManager instance not available, cannot directly control listening")
        }
        
        // Always emit mode changes so VoiceManager can stop/start listening appropriately
        Log.d(TAG, "[APPLY_MODE] Launching coroutine to emit mode change")
        serviceScope.launch {
            Log.d(TAG, "[APPLY_MODE] Emitting mode change: $currentMode")
            _modeChangeFlow.emit(currentMode)
            Log.d(TAG, "[APPLY_MODE] Mode change emitted successfully")
        }
        Log.d(TAG, "[APPLY_MODE] Completed applyCurrentMode")
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
                    if (transcription.isNotEmpty()) {
                        showMessage(transcription, isUserMessage = true)
                    }
                }
        }
    }
    
    private fun startBotResponseListener() {
        Log.d(TAG, "[BUBBLE_LISTENER] Starting bot response listener")
        botResponseJob = serviceScope.launch {
            botResponseFlow
                .filterNotNull()
                .distinctUntilChanged()
                .collect { response ->
                    Log.d(TAG, "[BUBBLE_LISTENER] Received bot response: '$response'")
                    // Filter out JSON responses (tool calls)
                    if (!response.startsWith("{") && !response.startsWith("[")) {
                        Log.d(TAG, "[BUBBLE_LISTENER] Showing bot message in bubble")
                        showMessage(response, isUserMessage = false)
                    } else {
                        Log.d(TAG, "[BUBBLE_LISTENER] Skipping JSON response")
                    }
                }
        }
    }

    private fun startForegroundListener() {
        Log.d(TAG, "Starting foreground listener")
        val serviceStartTime = System.currentTimeMillis()
        foregroundListenerJob = serviceScope.launch {
            appLifecycleService.appForegroundEvent.collect {
                val timeSinceStart = System.currentTimeMillis() - serviceStartTime
                // Add a 1 second grace period to prevent immediate self-destruction due to race conditions
                if (timeSinceStart < 1000) {
                    Log.d(TAG, "Ignoring app foreground event - service just started (${timeSinceStart}ms ago)")
                } else {
                    Log.d(TAG, "App foregrounded - stopping bubble overlay service")
                    stopSelf()
                }
            }
        }
    }
    
    private fun showMessage(text: String, isUserMessage: Boolean) {
        handler.post {
            val messageBubble = chatHeadView?.findViewById<CardView>(R.id.message_bubble)
            val messageText = chatHeadView?.findViewById<TextView>(R.id.message_text)
            
            // Cancel any pending hide
            hideMessageRunnable?.let { handler.removeCallbacks(it) }
            
            // Set message text and styling
            messageText?.text = text
            messageText?.setTypeface(
                messageText.typeface, 
                if (isUserMessage) android.graphics.Typeface.NORMAL else android.graphics.Typeface.ITALIC
            )
            
            messageBubble?.setCardBackgroundColor(
                resources.getColor(
                    if (isUserMessage) R.color.user_bubble else R.color.assistant_bubble,
                    null
                )
            )
            
            // Show message bubble
            messageBubble?.visibility = View.VISIBLE
            
            // Auto-hide message after delay
            hideMessageRunnable = Runnable {
                messageBubble?.visibility = View.GONE
            }
            handler.postDelayed(hideMessageRunnable!!, MESSAGE_DISPLAY_DURATION)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BubbleOverlayService onDestroy - setting isActive to false")
        isActive = false
        recognitionJob?.cancel()
        botResponseJob?.cancel()
        foregroundListenerJob?.cancel()
        hideMessageRunnable?.let { handler.removeCallbacks(it) }
        try {
            chatHeadView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing views", e)
        }
    }
}