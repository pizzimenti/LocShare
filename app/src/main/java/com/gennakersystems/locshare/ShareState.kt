package com.gennakersystems.locshare

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The share currently being broadcast by this device. */
data class ActiveShare(
    val token: String,
    val name: String,
    /** Epoch millis; 0 = forever. */
    val expiresAt: Long,
) {
    val url: String get() = Config.SHARE_BASE_URL + token
}

/**
 * Single source of truth for the active share, observed by the UI and updated by
 * both the UI and [LocationSharingService]. Persisted so the app reflects an
 * ongoing share after process restart.
 */
object ShareState {
    private const val PREFS = "share_state"

    private val _active = MutableStateFlow<ActiveShare?>(null)
    val active: StateFlow<ActiveShare?> = _active

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString("token", null) ?: return
        val expiresAt = p.getLong("expiresAt", 0L)
        if (expiresAt != 0L && expiresAt < System.currentTimeMillis()) {
            set(context, null)
        } else {
            _active.value = ActiveShare(token, p.getString("name", "") ?: "", expiresAt)
        }
    }

    fun set(context: Context, share: ActiveShare?) {
        _active.value = share
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (share == null) {
                clear()
            } else {
                putString("token", share.token)
                putString("name", share.name)
                putLong("expiresAt", share.expiresAt)
            }
            apply()
        }
    }
}
