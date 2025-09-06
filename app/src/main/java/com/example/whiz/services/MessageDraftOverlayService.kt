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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.graphics.Color
import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch
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
        private const val EXTRA_PREVIOUS_TEXT = "previous_text"
        
        @Volatile
        var isActive: Boolean = false
            private set
        
        @Volatile
        var currentDraftMessage: String? = null
            private set
        
        fun show(context: Context, bounds: Rect, message: String, previousText: String? = null): Boolean {
            return try {
                // Stop any existing instance
                if (isActive) {
                    stop(context)
                }
                
                val intent = Intent(context, MessageDraftOverlayService::class.java).apply {
                    putExtra(EXTRA_BOUNDS, bounds)
                    putExtra(EXTRA_MESSAGE, message)
                    previousText?.let { putExtra(EXTRA_PREVIOUS_TEXT, it) }
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
            val previousText = it.getStringExtra(EXTRA_PREVIOUS_TEXT)
            
            if (bounds != null && message != null) {
                createDraftOverlay(bounds, message, previousText)
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
    private fun createDraftOverlay(bounds: Rect, message: String, previousText: String?) {
        Log.d(TAG, "Creating draft overlay at bounds: $bounds with message: $message, previousText: $previousText")
        
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
        
        // Ensure the overlay is fully opaque (Note: Android 12+ enforces max 80% opacity for security)
        overlayView?.alpha = 1.0f
        overlayView?.findViewById<CardView>(R.id.draft_card)?.apply {
            alpha = 1.0f
            cardElevation = 8f
        }
        
        // Set the message text with track changes if previous text is provided
        val messageText = overlayView?.findViewById<TextView>(R.id.draft_message_text)
        if (previousText != null && previousText != message) {
            // Use setText with SPANNABLE buffer type to preserve formatting
            messageText?.setText(createTrackedChangesText(previousText, message), TextView.BufferType.SPANNABLE)
        } else {
            messageText?.text = message
        }
        
        // Use the width from bounds (which now contains the app's width)
        val overlayWidth = bounds.width()
        
        // Create layout parameters to position the overlay below the input field with app width
        val params = WindowManager.LayoutParams(
            overlayWidth,  // Use app width from bounds
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
            x = bounds.left  // Start from app's left edge
            // Position the overlay below the input field (keep it where it currently appears)
            y = bounds.top - 10  // Positioned relative to input field
        }
        
        Log.d(TAG, "Setting overlay position: x=${bounds.left}, y=${bounds.top - 10}, width=$overlayWidth (app width)")
        
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
    
    private fun createTrackedChangesText(oldText: String, newText: String): SpannableStringBuilder {
        val result = SpannableStringBuilder()
        
        Log.d(TAG, "Creating tracked changes - Old: '$oldText', New: '$newText'")
        
        // Use Google's diff-match-patch library for efficient diffing
        val dmp = DiffMatchPatch()
        // Set a reasonable timeout (1 second) for diff computation
        dmp.diffTimeout = 1.0f
        
        // Compute the diff at character level for accuracy
        val diffs = dmp.diffMain(oldText, newText)
        
        // Optional: Cleanup the diff for better readability (combines small changes)
        dmp.diffCleanupSemantic(diffs)
        
        Log.d(TAG, "Diff result: ${diffs.size} parts")
        
        // Build the spannable string from the diffs
        for (diff in diffs) {
            Log.d(TAG, "Diff part: operation=${diff.operation}, text='${diff.text}'")
            val start = result.length
            result.append(diff.text)
            val end = result.length
            
            when (diff.operation) {
                DiffMatchPatch.Operation.DELETE -> {
                    // Deleted text - red with strikethrough
                    Log.d(TAG, "Applying DELETE spans to text: '${diff.text}' (positions $start-$end)")
                    result.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    result.setSpan(ForegroundColorSpan(Color.RED), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffMatchPatch.Operation.INSERT -> {
                    // Inserted text - blue
                    Log.d(TAG, "Applying INSERT span to text: '${diff.text}' (positions $start-$end)")
                    result.setSpan(ForegroundColorSpan(Color.BLUE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffMatchPatch.Operation.EQUAL -> {
                    // Unchanged text - black (no styling needed)
                    Log.d(TAG, "No spans for EQUAL text: '${diff.text}'")
                }
                else -> {
                    // Should not happen, but handle gracefully
                    Log.w(TAG, "Unexpected diff operation: ${diff.operation}")
                }
            }
        }
        
        Log.d(TAG, "Final tracked changes text: '${result}' with ${result.getSpans(0, result.length, Any::class.java).size} spans")
        return result
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