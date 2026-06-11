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
import kotlin.math.roundToInt

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

        // Media-stream partial duck: focus-based ducking can't quiet apps that ignore the duck
        // (Google Maps nav, YouTube Music — both on STREAM_MUSIC). So while a listening session is
        // active we lower STREAM_MUSIC directly to this fraction of the user's level. Whiz's own TTS
        // is on STREAM_VOICE_CALL (USAGE_VOICE_COMMUNICATION, see TTSManager) so it is unaffected.
        private const val MEDIA_DUCK_FRACTION = 0.4f
        private const val DUCK_PREFS_NAME = "audio_focus_ducking"
        private const val KEY_SAVED_MUSIC_VOLUME = "saved_music_volume"
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var duckingReRequestJob: Job? = null
    private var duckingRetryCount: Int = 0
    private var intentionalDuckingAbandon: Boolean = false

    // Policy callback: VoiceManager sets this to decide if ducking should be re-requested
    var shouldReRequestDucking: (() -> Boolean)? = null

    // Media-stream partial duck state. The user's STREAM_MUSIC level before we lowered it; null means
    // we are not currently media-ducking. Persisted to prefs so an unclean shutdown can be recovered.
    private val duckPrefs = context.getSharedPreferences(DUCK_PREFS_NAME, Context.MODE_PRIVATE)
    private var savedMusicVolume: Int? = null

    init {
        // Crash/kill recovery: if a previous run died mid-duck (saved level persisted but never
        // restored), put STREAM_MUSIC back BEFORE any new ducking. Prevents the user's media volume
        // being left low, and prevents compounding (a later session saving the already-lowered level).
        val persisted = duckPrefs.getInt(KEY_SAVED_MUSIC_VOLUME, -1)
        if (persisted >= 0) {
            try {
                Log.d(TAG, "Recovering STREAM_MUSIC volume after unclean shutdown -> $persisted")
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, persisted, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover STREAM_MUSIC volume", e)
            }
            duckPrefs.edit().remove(KEY_SAVED_MUSIC_VOLUME).apply()
        }
    }

    /**
     * Request ducking focus to lower other apps' audio volume.
     * Uses AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so the system automatically ducks
     * other apps' audio by ~14dB while we hold focus.
     * @return true if ducking focus was granted
     */
    fun requestDuckingFocus(): Boolean {
        // Always ask the system rather than trusting our own state —
        // guards against silent focus revocation on some OEM devices.
        // requestAudioFocus() is idempotent: returns GRANTED if already held.
        // Reset the flag so that a previous intentional abandon doesn't
        // prevent re-requests after an external focus steal.
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
            // LOAD-BEARING: without this, when another app (e.g. Google Maps voice navigation, a
            // different uid) grabs GAIN_TRANSIENT_MAY_DUCK focus, Android SILENTLY auto-ducks us and
            // never calls onAudioFocusChange — so we never re-request and get left ducked *under*
            // the other app. setWillPauseWhenDucked(true) opts us out of silent auto-ducking: we
            // receive AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK instead and attemptDuckingReRequest() puts
            // us back on top so the other app ducks for us. Do not remove. (Verified red->green by
            // run_screen_agent_tests.py::test_google_maps_directions.)
            .setWillPauseWhenDucked(true)
            // EXPERIMENT: force ducking even on apps that handle ducking themselves and ignore the
            // polite duck (Google Maps nav keeps its voice at full volume otherwise — confirmed via
            // full-device logcat that the system never fades Maps). setForceDucking(true) is the only
            // focus-level mechanism that can override that, and it's only valid with MAY_DUCK.
            // CAVEAT: some Android versions honor forced ducking only for system/privileged apps and
            // silently ignore it for normal apps — verify by ear on-device; if Maps stays loud, this
            // is being ignored and we fall back to lowering the nav stream volume directly.
            .setForceDucking(true)
            .setOnAudioFocusChangeListener(this)
            .build()

        val result = audioManager.requestAudioFocus(duckingFocusRequest!!)
        _isDuckingActive.value = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        if (_isDuckingActive.value) {
            duckingRetryCount = 0
        }
        Log.d(TAG, "requestDuckingFocus: result=${if (_isDuckingActive.value) "GRANTED" else "FAILED($result)"}")

        // Also partial-duck STREAM_MUSIC directly — the apps we care about (Maps nav, YouTube Music)
        // ignore focus ducking, so the stream-volume reduction is what actually quiets them. Idempotent.
        applyMediaStreamDuck()
        return _isDuckingActive.value
    }

    /**
     * Abandon ducking focus so other apps resume normal volume.
     */
    fun abandonDuckingFocus() {
        // Restore STREAM_MUSIC first — media-duck state is independent of focus state, so it must run
        // even if the focus part below early-returns (e.g. focus already lost but media still ducked).
        restoreMediaStreamDuck()
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

    /**
     * Partial-duck the media stream (STREAM_MUSIC) so other apps' audio (YouTube Music, Google Maps
     * nav, all media — they all live on STREAM_MUSIC) is quieter and bleeds less into the mic during a
     * listening session. Whiz's own TTS is on STREAM_VOICE_CALL so it stays at full volume; alarms,
     * calls and notifications are on their own streams and are untouched.
     *
     * Idempotent: the user's level is saved exactly once per session, so the many re-requests that
     * happen during a session never re-save the already-lowered value.
     */
    private fun applyMediaStreamDuck() {
        if (savedMusicVolume != null) return  // already ducking this session
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedMusicVolume = current
            duckPrefs.edit().putInt(KEY_SAVED_MUSIC_VOLUME, current).apply()
            val target = (current * MEDIA_DUCK_FRACTION).roundToInt().coerceIn(0, current)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)  // no FLAG_SHOW_UI
            Log.d(TAG, "Media stream ducked: STREAM_MUSIC $current -> $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to duck media stream", e)
            savedMusicVolume = null
            duckPrefs.edit().remove(KEY_SAVED_MUSIC_VOLUME).apply()
        }
    }

    /**
     * Restore STREAM_MUSIC to the level saved by [applyMediaStreamDuck]. Safe to call repeatedly.
     * Does not fight the user: if the current media volume no longer matches the level we ducked to,
     * the user adjusted it themselves during the session, so we leave their choice alone.
     */
    private fun restoreMediaStreamDuck() {
        val saved = savedMusicVolume ?: return
        savedMusicVolume = null
        duckPrefs.edit().remove(KEY_SAVED_MUSIC_VOLUME).apply()
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val duckedTarget = (saved * MEDIA_DUCK_FRACTION).roundToInt().coerceIn(0, saved)
            if (current != duckedTarget) {
                Log.d(TAG, "Media stream restore skipped — user changed volume (current=$current, ducked=$duckedTarget)")
                return
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)  // no FLAG_SHOW_UI
            Log.d(TAG, "Media stream restored: STREAM_MUSIC -> $saved")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore media stream", e)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Log.d(TAG, "Audio focus changed: ${focusChangeToString(focusChange)}, isMusicActive=${audioManager.isMusicActive}")

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Ducking focus gained")
                _isDuckingActive.value = true
                duckingRetryCount = 0
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioManager.AUDIOFOCUS_LOSS -> {
                val wasDuckingActive = _isDuckingActive.value
                _isDuckingActive.value = false
                // For permanent LOSS we drop the request; for transient losses we keep it
                // so the existing AudioFocusRequest can be reused by attemptDuckingReRequest.
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                    duckingFocusRequest = null
                }

                if (wasDuckingActive && !intentionalDuckingAbandon) {
                    Log.d(TAG, "Ducking focus lost (${focusChangeToString(focusChange)}), attempting re-request")
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
