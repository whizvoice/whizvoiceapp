package com.example.whiz.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlin.math.pow
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

    @Inject
    lateinit var voiceManager: VoiceManager

    private lateinit var windowManager: WindowManager
    private var chatHeadView: View? = null
    private var dismissTargetView: View? = null
    private var dismissTargetParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var recognitionJob: Job? = null
    private var botResponseJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideMessageRunnable: Runnable? = null
    private var testTranscriptionReceiver: BroadcastReceiver? = null
    private var isDismissTargetVisible = false

    private var currentMode = ListeningMode.CONTINUOUS_LISTENING
    
    companion object {
        private const val TAG = "BubbleOverlayService"
        private const val CLICK_THRESHOLD = 10
        private const val MESSAGE_DISPLAY_DURATION = 5000L // 5 seconds
        private const val LONG_PRESS_THRESHOLD = 500L // 500ms for long press
        private const val DISMISS_TARGET_PROXIMITY = 400 // Distance in pixels to trigger dismiss target growth
        private const val DISMISS_TARGET_THRESHOLD = 400 // Distance in pixels to consider "over" the target - same as proximity

        // Track if the bubble overlay is active
        @Volatile
        var isActive: Boolean = false
            private set

        // Track the current listening mode
        @Volatile
        var bubbleListeningMode: ListeningMode = ListeningMode.CONTINUOUS_LISTENING

        // Store service instance for programmatic mode changes
        @Volatile
        private var serviceInstance: BubbleOverlayService? = null

        // Flows for displaying text in bubble
        // NOTE: replay = 0 to prevent test isolation issues - we don't want cached values replaying to new subscribers
        private val _botResponseFlow = MutableSharedFlow<String>(replay = 0)
        val botResponseFlow: SharedFlow<String> = _botResponseFlow

        private val _userTranscriptionFlow = MutableSharedFlow<String>(replay = 0)
        val userTranscriptionFlow: SharedFlow<String> = _userTranscriptionFlow

        // Flow for mode change notifications
        private val _modeChangeFlow = MutableSharedFlow<ListeningMode>(replay = 0)
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

        /**
         * Programmatically set the bubble listening mode.
         * This is used by voice control tools to change bubble mode based on server commands.
         *
         * @param mode The desired listening mode
         */
        fun setMode(mode: ListeningMode) {
            Log.d(TAG, "[SET_MODE] Programmatically setting mode to: $mode")
            serviceInstance?.let { service ->
                service.handler.post {
                    val previousMode = service.currentMode
                    service.currentMode = mode
                    Log.d(TAG, "[SET_MODE] Mode changed from $previousMode to $mode")
                    service.updateModeVisual()
                    service.applyCurrentMode()
                }
            } ?: run {
                Log.w(TAG, "[SET_MODE] Cannot set mode - service instance is null")
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BubbleOverlayService onCreate - setting isActive to true")
        isActive = true
        serviceInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Determine initial mode based on TTS state BEFORE backgrounding
        // ChatViewModel disables TTS on background, but we check the saved state
        // to see if user had TTS enabled before backgrounding
        val wasTTSEnabled = voiceManager.ttsStateBeforeBackground ?: voiceManager.isVoiceResponseEnabled.value
        currentMode = if (wasTTSEnabled) {
            Log.d(TAG, "BubbleOverlayService onCreate - TTS was enabled before background ($wasTTSEnabled), starting in TTS_WITH_LISTENING mode")
            ListeningMode.TTS_WITH_LISTENING
        } else {
            Log.d(TAG, "BubbleOverlayService onCreate - TTS was disabled before background ($wasTTSEnabled), starting in CONTINUOUS_LISTENING mode")
            ListeningMode.CONTINUOUS_LISTENING
        }

        createChatHead()
        updateModeVisual() // Set initial visual state
        applyCurrentMode() // Apply initial mode to VoiceManager

        // Collect transcriptions from VoiceManager flow and forward to bubble UI when active
        serviceScope.launch {
            voiceManager.transcriptionFlow.collect { transcription ->
                if (transcription.isNotBlank() && isActive) {
                    Log.d(TAG, "Transcription received from flow in bubble mode: '$transcription'")
                    _userTranscriptionFlow.emit(transcription)
                }
            }
        }

        startVoiceTranscriptionListener()
        startBotResponseListener()
        registerTestTranscriptionReceiver()
        createDismissTarget()
    }
    
    @SuppressLint("InflateParams")
    private fun createDismissTarget() {
        dismissTargetView = LayoutInflater.from(this).inflate(R.layout.bubble_dismiss_target, null)

        // Force circular clipping
        dismissTargetView?.clipToOutline = true
        dismissTargetView?.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND

        // Start scaled down (180dp * 0.667 = 120dp visual size)
        dismissTargetView?.scaleX = 0.667f
        dismissTargetView?.scaleY = 0.667f

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels

        dismissTargetParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            y = 100 // 100px from bottom
        }

        // Initially hidden
        dismissTargetView?.visibility = View.GONE

        try {
            windowManager.addView(dismissTargetView, dismissTargetParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add dismiss target view", e)
        }
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
        var isDragging = false

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
                    isDragging = false

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

                        // Show dismiss target when dragging starts
                        if (!isDragging) {
                            isDragging = true
                            showDismissTarget()
                        }
                    }

                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(chatHeadView, params)

                    // Update dismiss target size based on proximity
                    if (isDragging) {
                        updateDismissTargetProximity(params)
                    }

                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }

                    val clickDuration = System.currentTimeMillis() - touchStartTime
                    val xDiff = abs(event.rawX - initialTouchX)
                    val yDiff = abs(event.rawY - initialTouchY)

                    // Check if bubble is over dismiss target
                    if (isDragging && isOverDismissTarget(params)) {
                        hideDismissTarget()
                        stopSelf()
                        return@setOnTouchListener true
                    }

                    // Hide dismiss target after drag
                    if (isDragging) {
                        hideDismissTarget()
                    }

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
    
    private fun showDismissTarget() {
        dismissTargetView?.let { view ->
            if (!isDismissTargetVisible) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                isDismissTargetVisible = true
            }
        }
    }

    private fun hideDismissTarget() {
        dismissTargetView?.let { view ->
            if (isDismissTargetVisible) {
                view.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        view.visibility = View.GONE
                    }
                    .start()
                isDismissTargetVisible = false
            }
        }

    }

    private fun updateDismissTargetProximity(bubbleParams: WindowManager.LayoutParams) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Convert dp to pixels
        val dismissTargetHeightPx = (120 * displayMetrics.density).toInt() // 120dp circle

        // Get the actual chat head view position on screen
        val chatHead = chatHeadView?.findViewById<CardView>(R.id.chat_head)
        val location = IntArray(2)
        chatHead?.getLocationOnScreen(location)

        // Calculate center of the actual chat head circle
        val chatHeadWidth = chatHead?.width ?: 0
        val chatHeadHeight = chatHead?.height ?: 0
        val bubbleX = location[0] + (chatHeadWidth / 2)
        val bubbleY = location[1] + (chatHeadHeight / 2)

        // Dismiss target is at bottom center
        val targetX = screenWidth / 2
        val targetY = screenHeight - 100 - (dismissTargetHeightPx / 2)

        // Calculate distance
        val distance = kotlin.math.sqrt(
            ((bubbleX - targetX).toDouble().pow(2.0) + (bubbleY - targetY).toDouble().pow(2.0))
        ).toFloat()

        Log.d(TAG, "[DISMISS_TARGET] Bubble: ($bubbleX, $bubbleY), Target: ($targetX, $targetY), Distance: $distance, Threshold: $DISMISS_TARGET_PROXIMITY")

        // Scale the dismiss target based on proximity - snap to full size when within range
        dismissTargetView?.let { target ->
            val scale = if (distance < DISMISS_TARGET_PROXIMITY) {
                Log.d(TAG, "[DISMISS_TARGET] Scaling to 1.0x (within range)")
                1.0f // Full size (180dp)
            } else {
                0.667f // Small size (120dp equivalent)
            }

            // Use transform scaling for the target
            target.scaleX = scale
            target.scaleY = scale

            // Counter-scale the X icon to keep absolute size constant
            // When circle is small (0.667), icon should be 1.0 (appears 52dp)
            // When circle is big (1.0), icon should be 0.667 (appears 52dp)
            val icon = target.findViewById<ImageView>(R.id.dismiss_icon)
            val iconScale = 0.667f / scale  // Maintains constant absolute size
            icon?.scaleX = iconScale
            icon?.scaleY = iconScale
        }
    }

    private fun isOverDismissTarget(bubbleParams: WindowManager.LayoutParams): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Get the actual chat head view position on screen (same as in updateDismissTargetProximity)
        val chatHead = chatHeadView?.findViewById<CardView>(R.id.chat_head)
        val location = IntArray(2)
        chatHead?.getLocationOnScreen(location)

        // Calculate center of the actual chat head circle
        val chatHeadWidth = chatHead?.width ?: 0
        val chatHeadHeight = chatHead?.height ?: 0
        val bubbleX = location[0] + (chatHeadWidth / 2)
        val bubbleY = location[1] + (chatHeadHeight / 2)

        // Dismiss target position
        val dismissTargetHeightPx = (120 * displayMetrics.density).toInt()
        val targetX = screenWidth / 2
        val targetY = screenHeight - 100 - (dismissTargetHeightPx / 2)

        // Calculate distance
        val distance = kotlin.math.sqrt(
            ((bubbleX - targetX).toDouble().pow(2.0) + (bubbleY - targetY).toDouble().pow(2.0))
        ).toFloat()

        return distance < DISMISS_TARGET_THRESHOLD
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

        // Use the injected voiceManager directly
        Log.d(TAG, "[APPLY_MODE] Using injected VoiceManager for mode: $currentMode")

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
                // Re-enable voice response (it may have been disabled when app backgrounded)
                voiceManager.setVoiceResponseEnabled(true)
                Log.d(TAG, "[APPLY_MODE] Re-enabled voice response for TTS mode")
            }
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

    private fun registerTestTranscriptionReceiver() {
        testTranscriptionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val text = intent?.getStringExtra("text") ?: return
                val fromVoice = intent.getBooleanExtra("fromVoice", true)

                Log.d(TAG, "Received test transcription broadcast in bubble: '$text'")

                // Emit the transcription to the flow
                serviceScope.launch {
                    _userTranscriptionFlow.emit(text)
                    Log.d(TAG, "Emitted test transcription to flow: '$text'")
                }
            }
        }

        val filter = IntentFilter("com.example.whiz.TEST_TRANSCRIPTION_LOCAL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testTranscriptionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(testTranscriptionReceiver, filter)
        }
        Log.d(TAG, "Registered test transcription receiver")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BubbleOverlayService onDestroy - setting isActive to false")
        isActive = false
        serviceInstance = null

        // Clear saved TTS state since bubble session is ending
        voiceManager.ttsStateBeforeBackground = null
        Log.d(TAG, "Cleared saved TTS state on bubble service destroy")

        // Unregister test receiver
        testTranscriptionReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering test receiver", e)
            }
        }

        recognitionJob?.cancel()
        botResponseJob?.cancel()
        hideMessageRunnable?.let { handler.removeCallbacks(it) }
        try {
            chatHeadView?.let { windowManager.removeView(it) }
            dismissTargetView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing views", e)
        }

        // Cancel the service scope to clean up all coroutines and prevent test isolation issues
        // This ensures that the SharedFlows are properly cleaned up even though they now have replay = 0
        serviceScope.coroutineContext[Job]?.cancel()
    }
}