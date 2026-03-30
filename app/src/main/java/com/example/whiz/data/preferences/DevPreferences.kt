package com.example.whiz.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("dev_settings", Context.MODE_PRIVATE)

    private val _isDeveloperMode = MutableStateFlow(prefs.getBoolean(KEY_DEVELOPER_MODE, false))
    val isDeveloperMode: StateFlow<Boolean> = _isDeveloperMode

    fun setDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
        _isDeveloperMode.value = enabled
    }

    companion object {
        private const val KEY_DEVELOPER_MODE = "developer_mode_enabled"
    }
}
