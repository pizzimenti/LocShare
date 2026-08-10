package com.gennakersystems.locshare

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

/**
 * Foreground service that pushes the device location to the share's RTDB node
 * every [UPDATE_INTERVAL_MS] while a share is active.
 */
class LocationSharingService : Service() {

    companion object {
        private const val ACTION_START = "com.gennakersystems.locshare.START"
        private const val ACTION_STOP = "com.gennakersystems.locshare.STOP"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_EXPIRES_AT = "expiresAt"
        private const val CHANNEL_ID = "sharing"
        private const val NOTIFICATION_ID = 1
        const val UPDATE_INTERVAL_MS = 5_000L

        fun start(context: Context, share: ActiveShare) {
            val i = Intent(context, LocationSharingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TOKEN, share.token)
                .putExtra(EXTRA_NAME, share.name)
                .putExtra(EXTRA_EXPIRES_AT, share.expiresAt)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationSharingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private var token: String? = null
    private var shareExpiresAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val t = token ?: return
            // The postDelayed expiry timer is uptime-based and stalls in Doze,
            // so enforce expiry against the wall clock on every update too.
            if (shareExpiresAt != 0L && System.currentTimeMillis() >= shareExpiresAt) {
                stopSharing(deleteShare = true)
                return
            }
            FirebaseDatabase.getInstance().getReference("shares/$t/loc").setValue(
                mapOf(
                    "lat" to loc.latitude,
                    "lng" to loc.longitude,
                    "acc" to loc.accuracy.toDouble(),
                    "ts" to ServerValue.TIMESTAMP,
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val t = intent.getStringExtra(EXTRA_TOKEN)
                val name = intent.getStringExtra(EXTRA_NAME) ?: ""
                val expiresAt = intent.getLongExtra(EXTRA_EXPIRES_AT, 0L)
                if (t == null) {
                    stopSharing(deleteShare = false)
                } else if (expiresAt != 0L && expiresAt <= System.currentTimeMillis()) {
                    // Redelivered after the share already lapsed. Reclaim that
                    // node, but don't tear down a newer share that replaced it.
                    val active = ShareState.active.value
                    if (active == null || active.token == t) {
                        token = t
                        stopSharing(deleteShare = true)
                    } else {
                        // A newer share replaced this one. Queue the lapsed node
                        // for the next sweep and leave the newer share alone —
                        // stopSelf() here would stop the whole service, ending
                        // the uploads for the share that is actually live.
                        OwnedShares.markPendingDelete(this, t)
                        if (token == null) {
                            // Cold instance: this stale intent was redelivered
                            // into a process where nothing is broadcasting yet,
                            // so resume the share the app still shows as live
                            // rather than leaving it silently dead.
                            startSharing(active.token, active.name, active.expiresAt)
                        }
                    }
                } else {
                    startSharing(t, name, expiresAt)
                }
            }
            ACTION_STOP -> stopSharing(deleteShare = true)
        }
        return START_REDELIVER_INTENT
    }

    @SuppressLint("MissingPermission") // only started from UI after the permission grant
    private fun startSharing(t: String, name: String, expiresAt: Long) {
        token = t
        shareExpiresAt = expiresAt
        handler.removeCallbacksAndMessages(null)

        val notification = buildNotification(name, expiresAt)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())

        if (expiresAt != 0L) {
            handler.postDelayed({ stopSharing(deleteShare = true) }, expiresAt - System.currentTimeMillis())
        }
    }

    private fun stopSharing(deleteShare: Boolean) {
        handler.removeCallbacksAndMessages(null)
        fused.removeLocationUpdates(callback)
        // A restarted service instance carries no token of its own, so fall back
        // to the persisted share — otherwise Stop clears the UI without ever
        // deleting the node.
        val t = token ?: ShareState.active.value?.token
        if (deleteShare && t != null) {
            // Mark before issuing the delete: if it never reaches the server
            // (offline, or this process dies first) the next sweep retries it,
            // including for "forever" shares that no expiry would ever catch.
            OwnedShares.markPendingDelete(this, t)
            FirebaseDatabase.getInstance().getReference("shares/$t").removeValue()
                .addOnSuccessListener { OwnedShares.remove(this, t) }
        }
        token = null
        shareExpiresAt = 0L
        ShareState.set(this, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(name: String, expiresAt: Long): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW)
        )

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, LocationSharingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val untilText = if (expiresAt == 0L) {
            "until you stop it"
        } else {
            "ends " + android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(expiresAt))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Sharing location: $name")
            .setContentText("Live location is visible via your link, $untilText")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop sharing", stopIntent)
            .build()
    }
}
