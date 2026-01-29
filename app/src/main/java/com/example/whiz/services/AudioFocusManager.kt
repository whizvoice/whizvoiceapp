package com.example.whiz.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioFocusState {
    NOT_REQUESTED,         // Haven't requested focus
    FOCUS_GRANTED,         // We have audio focus
    FOCUS_LOST_TRANSIENT,  // Temporarily lost (e.g., Google Maps speaking)
    FOCUS_LOST_PERMANENT   // Permanently lost (e.g., music app took over)
}

@Singleton
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioManager.OnAudioFocusChangeListener {

    private val TAG = "AudioFocusManager"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _focusState = MutableStateFlow(AudioFocusState.NOT_REQUESTED)
    val focusState: StateFlow<AudioFocusState> = _focusState.asStateFlow()

    private var audioFocusRequest: AudioFocusRequest? = null

    // Callbacks for focus changes - VoiceManager will set these
    var onFocusGained: (() -> Unit)? = null
    var onFocusLostTransient: (() -> Unit)? = null
    var onFocusLostPermanent: (() -> Unit)? = null

    /**
     * Request audio focus for voice recording.
     * Uses AUDIOFOCUS_GAIN for continuous listening.
     * @return true if focus was granted immediately
     */
    fun requestFocus(): Boolean {
        if (_focusState.value == AudioFocusState.FOCUS_GRANTED) {
            Log.d(TAG, "Already have audio focus")
            return true
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(this)
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                Log.d(TAG, "Audio focus granted")
                _focusState.value = AudioFocusState.FOCUS_GRANTED
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                Log.d(TAG, "Audio focus request delayed")
                false
            }
            else -> {
                Log.w(TAG, "Audio focus request failed: $result")
                false
            }
        }
    }

    /**
     * Abandon audio focus when done listening.
     */
    fun abandonFocus() {
        if (_focusState.value == AudioFocusState.NOT_REQUESTED) {
            return
        }

        Log.d(TAG, "Abandoning audio focus")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }

        _focusState.value = AudioFocusState.NOT_REQUESTED
        audioFocusRequest = null
    }

    fun isHoldingFocus(): Boolean {
        return _focusState.value == AudioFocusState.FOCUS_GRANTED
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "Audio focus changed: ${focusChangeToString(focusChange)}")

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained")
                _focusState.value = AudioFocusState.FOCUS_GRANTED
                onFocusGained?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transiently")
                _focusState.value = AudioFocusState.FOCUS_LOST_TRANSIENT
                onFocusLostTransient?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Audio focus lost permanently")
                _focusState.value = AudioFocusState.FOCUS_LOST_PERMANENT
                onFocusLostPermanent?.invoke()
            }
        }
    }

    private fun focusChangeToString(focusChange: Int): String {
        return when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            else -> "UNKNOWN($focusChange)"
        }
    }
}
