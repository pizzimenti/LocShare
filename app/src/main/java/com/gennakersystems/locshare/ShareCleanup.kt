package com.gennakersystems.locshare

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Tokens this device has created, kept until the remote node is known to be gone.
 *
 * The database has no server-side TTL (scheduled Cloud Functions need the Blaze
 * plan), and only the creating anonymous user may delete a share — so the owning
 * device is the only party able to reclaim its own nodes.
 *
 * Stored as `"<expiresAt>|<attempts>"`:
 *  - `expiresAt` epoch millis, [FOREVER] for a share with no expiry, or
 *    [PENDING_DELETE] once a delete has been requested but not confirmed.
 *  - `attempts` counts consecutive failed deletes so a permanently impossible
 *    delete (e.g. the owning uid was lost) is eventually abandoned instead of
 *    retried on every launch forever.
 */
object OwnedShares {
    private const val PREFS = "owned_shares"
    private const val TAG = "OwnedShares"

    const val FOREVER = 0L
    const val PENDING_DELETE = -1L

    /** Give up after this many consecutive failures; the node is unreclaimable. */
    const val MAX_ATTEMPTS = 5

    private data class Entry(val expiresAt: Long, val attempts: Int)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parse(raw: Any?): Entry? = when (raw) {
        // Current format.
        is String -> raw.split('|').let { parts ->
            parts.getOrNull(0)?.toLongOrNull()?.let { Entry(it, parts.getOrNull(1)?.toIntOrNull() ?: 0) }
        }
        // Entries written before attempts were tracked.
        is Long -> Entry(raw, 0)
        else -> null
    }

    private fun write(context: Context, token: String, entry: Entry) {
        prefs(context).edit()
            .putString(token, "${entry.expiresAt}|${entry.attempts}")
            .apply()
    }

    fun add(context: Context, token: String, expiresAt: Long) {
        write(context, token, Entry(expiresAt, 0))
    }

    /**
     * Marks a token for deletion on the next sweep regardless of its expiry.
     * Used when a delete was requested but may not have reached the server —
     * without this, a failed stop on a [FOREVER] share would never be retried.
     */
    fun markPendingDelete(context: Context, token: String) {
        val attempts = parse(prefs(context).all[token])?.attempts ?: 0
        write(context, token, Entry(PENDING_DELETE, attempts))
    }

    fun remove(context: Context, token: String) {
        prefs(context).edit().remove(token).apply()
    }

    fun contains(context: Context, token: String): Boolean =
        prefs(context).contains(token)

    /**
     * Tokens whose node should be deleted now: those explicitly pending delete,
     * plus those whose expiry has passed by more than [skewMarginMs].
     *
     * [skipToken] (the share currently being broadcast) is never returned unless
     * it is explicitly pending delete, so a sweep can't pull the node out from
     * under a live share.
     */
    fun due(context: Context, now: Long, skewMarginMs: Long, skipToken: String?): List<String> =
        prefs(context).all.mapNotNull { (token, raw) ->
            val entry = parse(raw) ?: return@mapNotNull null
            if (entry.attempts >= MAX_ATTEMPTS) return@mapNotNull null
            val pending = entry.expiresAt == PENDING_DELETE
            if (!pending && token == skipToken) return@mapNotNull null
            val lapsed = entry.expiresAt > 0L && entry.expiresAt + skewMarginMs <= now
            if (pending || lapsed) token else null
        }

    /** Records a failed delete; drops the entry once [MAX_ATTEMPTS] is reached. */
    fun recordFailure(context: Context, token: String) {
        val entry = parse(prefs(context).all[token]) ?: return
        val next = entry.copy(attempts = entry.attempts + 1)
        if (next.attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "giving up on $token after ${next.attempts} attempts; node may be orphaned")
            remove(context, token)
        } else {
            write(context, token, next)
        }
    }
}

object ShareCleanup {
    private const val TAG = "ShareCleanup"

    /** How far past expiry to wait before deleting, absorbing residual clock skew. */
    private const val SKEW_MARGIN_MS = 60_000L
    private const val DELETE_TIMEOUT_MS = 15_000L
    private const val OFFSET_TIMEOUT_MS = 3_000L

    /**
     * Deletes this device's shares that have lapsed or been explicitly stopped.
     *
     * Safe to call on every foreground entry: it returns immediately when nothing
     * is due. Runs entirely off the main thread. Failures are left in
     * [OwnedShares] to retry, up to [OwnedShares.MAX_ATTEMPTS].
     */
    suspend fun sweep(context: Context) = withContext(Dispatchers.IO) {
        if (!Config.isConfigured) return@withContext

        // Shares created by an earlier build predate OwnedShares; adopt the one
        // ShareState knows about so it is not stranded outside every sweep.
        ShareState.active.value?.let { active ->
            if (!OwnedShares.contains(context, active.token)) {
                OwnedShares.add(context, active.token, active.expiresAt)
            }
        }

        val db = FirebaseDatabase.getInstance()
        // The server enforces expiry with its own clock, so correct for skew
        // rather than trusting the device clock outright.
        val now = System.currentTimeMillis() + serverTimeOffset(db)
        val activeToken = ShareState.active.value?.token
        val due = OwnedShares.due(context, now, SKEW_MARGIN_MS, activeToken)
        if (due.isEmpty()) return@withContext

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            try {
                auth.signInAnonymously().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sign-in failed; deferring cleanup of ${due.size} share(s)", e)
                return@withContext
            }
        }

        for (token in due) {
            try {
                // An RTDB write Task only completes on server ack, so without a
                // timeout this suspends indefinitely while offline.
                withTimeout(DELETE_TIMEOUT_MS) {
                    db.getReference("shares/$token").removeValue().await()
                }
                OwnedShares.remove(context, token)
                Log.i(TAG, "reclaimed share $token")
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "timed out reclaiming $token; will retry", e)
                OwnedShares.recordFailure(context, token)
            } catch (e: CancellationException) {
                // The caller's scope was cancelled — leave the entry for the
                // next sweep and honour the cancellation.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not reclaim $token; will retry", e)
                OwnedShares.recordFailure(context, token)
            }
        }
    }

    /**
     * Difference between server and device clocks, from the SDK's locally cached
     * `.info/serverTimeOffset`. Falls back to 0 (trust the device) if unavailable.
     */
    private suspend fun serverTimeOffset(db: FirebaseDatabase): Long =
        withTimeoutOrNull(OFFSET_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val ref = db.getReference(".info/serverTimeOffset")
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (cont.isActive) cont.resume(snapshot.getValue(Long::class.java) ?: 0L)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (cont.isActive) cont.resume(0L)
                    }
                }
                ref.addListenerForSingleValueEvent(listener)
                cont.invokeOnCancellation { ref.removeEventListener(listener) }
            }
        } ?: 0L
}
