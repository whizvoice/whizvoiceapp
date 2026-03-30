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
import android.view.View
import android.widget.ImageView
import android.view.WindowManager
import android.widget.TextView
import androidx.cardview.widget.CardView
import android.view.ContextThemeWrapper
import com.example.whiz.R
import com.google.android.material.color.DynamicColors
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
    private val themedContext: Context by lazy {
        DynamicColors.wrapContextIfAvailable(ContextThemeWrapper(this, R.style.Theme_Whiz))
    }
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var dismissButtonView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null
    
    companion object {
        private const val TAG = "MessageDraftOverlay"
        private const val AUTO_DISMISS_DELAY = 25000L // 25 seconds
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
                // Don't stop existing instance - just send new intent to update it
                // This ensures the auto-dismiss timer gets properly reset
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
    
    @SuppressLint("InflateParams")
    private fun createDraftOverlay(bounds: Rect, message: String, previousText: String?) {
        Log.d(TAG, "Creating draft overlay at bounds: $bounds with message: $message, previousText: $previousText")
        
        // Store the draft message for later use by confirm_send
        currentDraftMessage = message
        
        // Remove any existing overlay and dismiss button
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing existing overlay", e)
            }
        }
        dismissButtonView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing existing dismiss button", e)
            }
        }
        
        // Inflate the overlay layout
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.message_draft_overlay, null)
        
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
        
        // Get the real screen height including navigation bar
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val realScreenHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val realSize = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(realSize)
            realSize.y
        }

        // Calculate height from the input field position to actual bottom of screen
        val overlayHeight = realScreenHeight - bounds.top
        
        // Create layout parameters to position the overlay below the input field with app width
        val params = WindowManager.LayoutParams(
            overlayWidth,  // Use app width from bounds
            overlayHeight,  // Extend from input field to bottom of screen
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
            // Position the overlay at the input field's top position
            y = bounds.top  // Positioned at input field
        }
        
        Log.d(TAG, "Setting overlay position: x=${bounds.left}, y=${bounds.top}, width=$overlayWidth (app width), height=$overlayHeight, realScreenHeight=$realScreenHeight")
        
        try {
            windowManager.addView(overlayView, params)
            Log.d(TAG, "Draft overlay added successfully")

            // Add dismiss button overlay
            createDismissButton(bounds)

            // Schedule auto-dismiss
            scheduleAutoDismiss()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add draft overlay", e)
            stopSelf()
        }
    }
    
    private fun createDismissButton(bounds: Rect) {
        val density = resources.displayMetrics.density
        val buttonSize = (48 * density).toInt() // 48dp touch target
        val padding = (12 * density).toInt() // 12dp padding around 24dp icon
        val inset = (4 * density).toInt() // 4dp inset from card edge

        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(padding, padding, padding, padding)
            contentDescription = "Dismiss draft"
            visibility = View.INVISIBLE // Hidden until positioned correctly
            setOnClickListener {
                Log.d(TAG, "Dismiss button tapped")
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.right - buttonSize - inset
            y = bounds.top + inset // Initial guess; corrected after layout
        }

        try {
            windowManager.addView(imageView, params)
            dismissButtonView = imageView

            // After layout, measure actual positions and correct for coordinate
            // system differences between touchable and non-touchable overlays
            imageView.post {
                val overlayLoc = IntArray(2)
                overlayView?.getLocationOnScreen(overlayLoc)

                val buttonLoc = IntArray(2)
                imageView.getLocationOnScreen(buttonLoc)

                val desiredScreenY = overlayLoc[1] + inset
                val correction = buttonLoc[1] - desiredScreenY
                if (correction != 0) {
                    params.y -= correction
                    windowManager.updateViewLayout(imageView, params)
                    Log.d(TAG, "Dismiss button corrected by ${correction}px")
                }
                imageView.visibility = View.VISIBLE
            }
            Log.d(TAG, "Dismiss button added at x=${params.x}, y=${params.y}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add dismiss button", e)
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
        
        // Remove overlay and dismiss button
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay", e)
        }
        try {
            dismissButtonView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing dismiss button", e)
        }
    }
}