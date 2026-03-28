package com.example.whiz.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioManager.OnAudioFocusChangeListener {

    private val TAG = "AudioFocusManager"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Ducking focus: used to duck other apps' audio during continuous listening sessions
    private var duckingFocusRequest: AudioFocusRequest? = null
    private val _isDuckingActive = MutableStateFlow(false)
    val isDuckingActive: StateFlow<Boolean> = _isDuckingActive.asStateFlow()

    // Ducking re-request support: when another app steals focus, we re-request
    // ducking so the other app ducks its volume while Whiz is active.
    companion object {
        private const val DUCKING_RE_REQUEST_DELAY_MS = 500L
        private const val MAX_DUCKING_RETRIES = 3
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var duckingReRequestJob: Job? = null
    private var duckingRetryCount: Int = 0
    private var intentionalDuckingAbandon: Boolean = false

    // Policy callback: VoiceManager sets this to decide if ducking should be re-requested
    var shouldReRequestDucking: (() -> Boolean)? = null

    /**
     * Request ducking focus to lower other apps' audio volume.
     * Uses AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so the system automatically ducks
     * other apps' audio by ~14dB while we hold focus.
     * @return true if ducking focus was granted
     */
    fun requestDuckingFocus(): Boolean {
        intentionalDuckingAbandon = false
        // Always ask the system rather than trusting our own state —
        // guards against silent focus revocation on some OEM devices.
        // requestAudioFocus() is idempotent: returns GRANTED if already held.

        // Bug fix: reset the flag so that a previous intentional abandon
        // doesn't prevent re-requests after an external focus steal
        intentionalDuckingAbandon = false

        // Clean up any stale request before creating a new one
        duckingFocusRequest?.let {
            Log.d(TAG, "Abandoning stale ducking request before creating new one")
            audioManager.abandonAudioFocusRequest(it)
        }

        // Bug fix: reset the flag so that a previous intentional abandon
        // doesn't prevent re-requests after an external focus steal
        intentionalDuckingAbandon = false

        // Clean up any stale request before creating a new one
        duckingFocusRequest?.let {
            Log.d(TAG, "Abandoning stale ducking request before creating new one")
            audioManager.abandonAudioFocusRequest(it)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        duckingFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(this)
            .build()

        val result = audioManager.requestAudioFocus(duckingFocusRequest!!)
        _isDuckingActive.value = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (_isDuckingActive.value) {
            duckingRetryCount = 0
        }
        Log.d(TAG, "requestDuckingFocus: result=${if (_isDuckingActive.value) "GRANTED" else "FAILED($result)"}")
        return _isDuckingActive.value
    }

    /**
     * Abandon ducking focus so other apps resume normal volume.
     */
    fun abandonDuckingFocus() {
        if (!_isDuckingActive.value && duckingFocusRequest == null) return
        Log.d(TAG, "Abandoning ducking focus")
        duckingReRequestJob?.cancel()
        duckingReRequestJob = null
        intentionalDuckingAbandon = true
        duckingRetryCount = 0
        duckingFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        _isDuckingActive.value = false
        duckingFocusRequest = null
    }

    fun isHoldingDuckingFocus(): Boolean {
        return _isDuckingActive.value
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "Audio focus changed: ${focusChangeToString(focusChange)}")

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Ducking focus gained")
                _isDuckingActive.value = true
                duckingRetryCount = 0
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus lost transiently (ignored for ducking)")
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "Ducking focus lost")
                val wasDuckingActive = _isDuckingActive.value
                _isDuckingActive.value = false
                duckingFocusRequest = null

                // If ducking was active and we didn't intentionally abandon it,
                // another app stole focus — try to re-request ducking
                if (wasDuckingActive && !intentionalDuckingAbandon) {
                    Log.d(TAG, "Ducking focus was stolen externally, attempting re-request")
                    attemptDuckingReRequest()
                } else if (intentionalDuckingAbandon) {
                    Log.d(TAG, "Ducking focus loss was intentional, not re-requesting")
                    intentionalDuckingAbandon = false
                }
            }
        }
    }

    private fun attemptDuckingReRequest() {
        // Cancel any pending re-request (debounce)
        duckingReRequestJob?.cancel()

        if (duckingRetryCount >= MAX_DUCKING_RETRIES) {
            Log.w(TAG, "Max ducking retries ($MAX_DUCKING_RETRIES) exceeded, giving up")
            duckingRetryCount = 0
            return
        }

        // Check policy callback — bail if VoiceManager says we shouldn't re-request
        if (shouldReRequestDucking?.invoke() != true) {
            Log.d(TAG, "shouldReRequestDucking returned false, not re-requesting")
            return
        }

        duckingRetryCount++
        Log.d(TAG, "Scheduling ducking re-request (attempt $duckingRetryCount/$MAX_DUCKING_RETRIES)")

        duckingReRequestJob = coroutineScope.launch {
            delay(DUCKING_RE_REQUEST_DELAY_MS)

            // Re-check policy after delay (conditions may have changed)
            if (shouldReRequestDucking?.invoke() != true) {
                Log.d(TAG, "shouldReRequestDucking returned false after delay, aborting re-request")
                return@launch
            }

            requestDuckingFocus()
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
