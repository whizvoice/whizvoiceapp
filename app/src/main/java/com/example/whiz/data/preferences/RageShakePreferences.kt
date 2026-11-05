package com.example.whiz.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent on/off switch for the shake-to-report-a-bug feature.
 * Distinct from the detector's 10-minute snooze, which is in-memory only.
 */
@Singleton
class RageShakePreferences @VisibleForTesting constructor(
    private val prefs: SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("rage_shake_settings", Context.MODE_PRIVATE))

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val isEnabled: StateFlow<Boolean> = _isEnabled

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabled.value = enabled
    }

    companion object {
        private const val KEY_ENABLED = "rage_shake_enabled"
    }
}
