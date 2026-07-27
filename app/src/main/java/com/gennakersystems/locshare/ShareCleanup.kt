package com.gennakersystems.locshare

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * Tokens this device has created, with their expiry, kept until the remote node
 * is known to be gone.
 *
 * The database has no server-side TTL (scheduled Cloud Functions need the Blaze
 * plan), and only the creating anonymous user may delete a share — so the owning
 * device is the only party able to reclaim its own expired nodes.
 */
object OwnedShares {
    private const val PREFS = "owned_shares"

    /** value is epoch millis of expiry; 0 = never expires. */
    fun add(context: Context, token: String, expiresAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(token, expiresAt).apply()
    }

    fun remove(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(token).apply()
    }

    /** Tokens whose expiry has passed. "Forever" shares (0) are never included. */
    fun expiredBefore(context: Context, now: Long): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .filterValues { it is Long && it != 0L && it <= now }
            .keys.toList()
}

object ShareCleanup {
    private const val TAG = "ShareCleanup"

    /**
     * Deletes any of this device's shares whose duration has elapsed. Runs on
     * app launch to catch shares that expired while the process was dead — the
     * foreground service deletes its own share when it is alive at expiry.
     *
     * Failures are left in [OwnedShares] to retry on the next launch.
     */
    suspend fun sweep(context: Context) {
        if (!Config.isConfigured) return
        val stale = OwnedShares.expiredBefore(context, System.currentTimeMillis())
        if (stale.isEmpty()) return

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            try {
                auth.signInAnonymously().await()
            } catch (e: Exception) {
                Log.w(TAG, "sign-in failed; deferring cleanup of ${stale.size} share(s)", e)
                return
            }
        }

        val db = FirebaseDatabase.getInstance()
        for (token in stale) {
            try {
                db.getReference("shares/$token").removeValue().await()
                OwnedShares.remove(context, token)
                Log.i(TAG, "removed expired share $token")
            } catch (e: Exception) {
                Log.w(TAG, "could not remove expired share $token; will retry", e)
            }
        }
    }
}
