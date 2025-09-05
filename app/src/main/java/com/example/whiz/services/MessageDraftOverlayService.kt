package com.example.whiz.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.example.whiz.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MessageDraftOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null
    
    companion object {
        private const val TAG = "MessageDraftOverlay"
        private const val AUTO_DISMISS_DELAY = 10000L // 10 seconds
        private const val EXTRA_BOUNDS = "bounds"
        private const val EXTRA_MESSAGE = "message"
        
        @Volatile
        var isActive: Boolean = false
            private set
        
        @Volatile
        var currentDraftMessage: String? = null
            private set
        
        fun show(context: Context, bounds: Rect, message: String): Boolean {
            return try {
                // Stop any existing instance
                if (isActive) {
                    stop(context)
                }
                
                val intent = Intent(context, MessageDraftOverlayService::class.java).apply {
                    putExtra(EXTRA_BOUNDS, bounds)
                    putExtra(EXTRA_MESSAGE, message)
                }
                context.startService(intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start draft overlay service", e)
                false
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, MessageDraftOverlayService::class.java)
            context.stopService(intent)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MessageDraftOverlayService onCreate")
        isActive = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MessageDraftOverlayService onStartCommand")
        
        intent?.let {
            val bounds = it.getParcelableExtra<Rect>(EXTRA_BOUNDS)
            val message = it.getStringExtra(EXTRA_MESSAGE)
            
            if (bounds != null && message != null) {
                createDraftOverlay(bounds, message)
            } else {
                Log.e(TAG, "Missing required extras: bounds=$bounds, message=$message")
                stopSelf()
            }
        } ?: run {
            Log.e(TAG, "Intent is null")
            stopSelf()
        }
        
        return START_NOT_STICKY
    }
    
    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun createDraftOverlay(bounds: Rect, message: String) {
        Log.d(TAG, "Creating draft overlay at bounds: $bounds with message: $message")
        
        // Store the draft message for later use by confirm_send
        currentDraftMessage = message
        
        // Remove any existing overlay
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing existing overlay", e)
            }
        }
        
        // Inflate the overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.message_draft_overlay, null)
        
        // Set the message text
        val messageText = overlayView?.findViewById<TextView>(R.id.draft_message_text)
        messageText?.text = message
        
        // Create layout parameters to position the overlay over the input field
        val params = WindowManager.LayoutParams(
            bounds.width(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }
        
        // Make the overlay clickable to dismiss
        overlayView?.findViewById<CardView>(R.id.draft_card)?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    Log.d(TAG, "Overlay tapped, dismissing")
                    stopSelf()
                    true
                }
                else -> false
            }
        }
        
        try {
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Draft overlay added successfully")
            
            // Schedule auto-dismiss
            scheduleAutoDismiss()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add draft overlay", e)
            stopSelf()
        }
    }
    
    private fun scheduleAutoDismiss() {
        // Cancel any existing auto-dismiss
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        
        // Schedule new auto-dismiss
        autoDismissRunnable = Runnable {
            Log.d(TAG, "Auto-dismissing draft overlay")
            stopSelf()
        }
        handler.postDelayed(autoDismissRunnable!!, AUTO_DISMISS_DELAY)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MessageDraftOverlayService onDestroy")
        isActive = false
        // Don't clear the draft message on destroy - keep it for confirm_send
        
        // Cancel auto-dismiss
        autoDismissRunnable?.let { handler.removeCallbacks(it) }
        
        // Remove overlay
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay", e)
        }
    }
}