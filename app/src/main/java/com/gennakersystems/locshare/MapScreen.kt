package com.gennakersystems.locshare

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.security.SecureRandom
import kotlin.math.roundToInt

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SRC_DOT = "me-dot"
private const val SRC_ACC = "me-accuracy"
private const val DOT_COLOR = "#4285F4"

/** How long to hold out for a precise fix before zooming in on a coarse one. */
private const val PRECISION_WAIT_MS = 20_000L

private val DURATION_CHOICES: List<Pair<Long, String>> = run {
    val m = 60_000L
    val h = 60 * m
    val d = 24 * h
    listOf(
        5 * m to "5 minutes",
        15 * m to "15 minutes",
        30 * m to "30 minutes",
        1 * h to "1 hour",
        2 * h to "2 hours",
        4 * h to "4 hours",
        8 * h to "8 hours",
        12 * h to "12 hours",
        1 * d to "24 hours",
        2 * d to "2 days",
        7 * d to "7 days",
        0L to "Forever",
    )
}
private const val DEFAULT_DURATION_INDEX = 3 // 1 hour

@SuppressLint("MissingPermission") // MapScreen is only shown after the permission grant
@Composable
fun MapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var location by remember { mutableStateOf<Location?>(null) }
    val follow = remember { mutableStateOf(true) }
    val locked = remember { mutableStateOf(false) } // true once zoomed to the 30 m viewport
    val firstFixAt = remember { mutableStateOf<Long?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    val active by ShareState.active.collectAsState()

    // Display-rate location stream (1 s) for the on-screen dot; the 5 s upload
    // stream lives in LocationSharingService.
    DisposableEffect(Unit) {
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location = it }
            }
        }
        fused.requestLocationUpdates(request, cb, Looper.getMainLooper())
        onDispose { fused.removeLocationUpdates(cb) }
    }

    val mapView = rememberMapViewWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.also { mv ->
                    mv.getMapAsync { m ->
                        map = m
                        m.uiSettings.isRotateGesturesEnabled = false
                        m.uiSettings.isTiltGesturesEnabled = false
                        m.addOnCameraMoveStartedListener { reason ->
                            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                follow.value = false
                            }
                        }
                        m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                            style.addSource(GeoJsonSource(SRC_ACC))
                            style.addSource(GeoJsonSource(SRC_DOT))
                            style.addLayer(
                                FillLayer("accuracy-fill", SRC_ACC)
                                    .withProperties(fillColor(DOT_COLOR), fillOpacity(0.12f))
                            )
                            style.addLayer(
                                LineLayer("accuracy-line", SRC_ACC)
                                    .withProperties(lineColor(DOT_COLOR), lineOpacity(0.4f), lineWidth(1f))
                            )
                            style.addLayer(
                                CircleLayer("dot-halo", SRC_DOT)
                                    .withProperties(circleRadius(9f), circleColor("#FFFFFF"))
                            )
                            style.addLayer(
                                CircleLayer("dot-core", SRC_DOT)
                                    .withProperties(circleRadius(6f), circleColor(DOT_COLOR))
                            )
                            styleReady = true
                        }
                    }
                }
            },
        )

        // Feed location into the map layers and drive the camera.
        LaunchedEffect(location, styleReady) {
            val loc = location ?: return@LaunchedEffect
            val m = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            val style = m.style ?: return@LaunchedEffect

            style.getSourceAs<GeoJsonSource>(SRC_DOT)
                ?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
            style.getSourceAs<GeoJsonSource>(SRC_ACC)
                ?.setGeoJson(Geo.circlePolygon(loc.latitude, loc.longitude, loc.accuracy.toDouble()))

            if (firstFixAt.value == null) firstFixAt.value = SystemClock.elapsedRealtime()
            // Indoors the fix may never get to 8 m. Zoom in anyway once we have
            // waited long enough, rather than leaving the map parked wide open.
            val waitedTooLong =
                SystemClock.elapsedRealtime() - (firstFixAt.value ?: 0L) >= PRECISION_WAIT_MS

            if (!locked.value && (loc.accuracy <= Geo.PRECISE_ACCURACY_METERS || waitedTooLong)) {
                locked.value = true
                follow.value = true
                m.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        Geo.boundsAround(loc.latitude, loc.longitude), 0
                    ),
                    800,
                )
            } else if (locked.value && follow.value) {
                m.easeCamera(
                    CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)), 500
                )
            }
        }

        // Top status overlays.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!Config.isConfigured) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        "Firebase not configured — sharing disabled",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (!locked.value) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                ) {
                    val loc = location
                    Text(
                        if (loc == null) "Waiting for location…"
                        else "Acquiring precise fix… ±${loc.accuracy.roundToInt()} m",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Bottom controls.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            if (locked.value && !follow.value) {
                SmallFloatingActionButton(
                    onClick = {
                        val loc = location
                        val m = map
                        if (loc != null && m != null) {
                            follow.value = true
                            m.animateCamera(
                                CameraUpdateFactory.newLatLngBounds(
                                    Geo.boundsAround(loc.latitude, loc.longitude), 0
                                ),
                                600,
                            )
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Re-center")
                }
            }

            val currentShare = active
            if (currentShare == null) {
                Button(
                    onClick = { showShareDialog = true },
                    enabled = Config.isConfigured && location != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Share Location", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                ActiveShareCard(
                    share = currentShare,
                    onStop = { LocationSharingService.stop(context) },
                )
            }
        }
    }

    if (showShareDialog) {
        ShareDialog(
            onDismiss = { showShareDialog = false },
            onConfirm = { name, durationMs ->
                showShareDialog = false
                scope.launch {
                    try {
                        startShare(context, name, durationMs, location)
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Couldn't start share: ${e.message}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}

private suspend fun startShare(
    context: Context,
    name: String,
    durationMs: Long,
    lastLocation: Location?,
) {
    val auth = FirebaseAuth.getInstance()
    if (auth.currentUser == null) auth.signInAnonymously().await()
    val uid = auth.currentUser?.uid ?: error("anonymous sign-in failed")

    val token = randomToken()
    val expiresAt = if (durationMs == 0L) 0L else System.currentTimeMillis() + durationMs
    val data = mutableMapOf<String, Any>(
        "name" to name,
        "owner" to uid,
        "createdAt" to ServerValue.TIMESTAMP,
        "expiresAt" to expiresAt,
    )
    lastLocation?.let {
        data["loc"] = mapOf(
            "lat" to it.latitude,
            "lng" to it.longitude,
            "acc" to it.accuracy.toDouble(),
            "ts" to ServerValue.TIMESTAMP,
        )
    }
    // Register before the write. A node that reaches the server while this
    // coroutine is cancelled (it lives in the composable's scope, so a rotation
    // is enough) would otherwise be invisible to every cleanup path. Registering
    // a token whose write never landed is harmless: deleting a node that does
    // not exist succeeds.
    // The write is synchronous, so it goes to the IO dispatcher; if it fails
    // there is no durable record of the token, and creating the node anyway
    // would be creating one nothing can reclaim.
    val registered = withContext(Dispatchers.IO) { OwnedShares.add(context, token, expiresAt) }
    if (!registered) error("could not record share ownership")
    try {
        FirebaseDatabase.getInstance().getReference("shares/$token").setValue(data).await()
    } catch (e: CancellationException) {
        // The write may still land, and the share was never handed to the user,
        // so queue it for reclamation instead of leaving it live.
        OwnedShares.markPendingDelete(context, token)
        throw e
    } catch (e: Exception) {
        OwnedShares.remove(context, token)
        throw e
    }

    val share = ActiveShare(token, name, expiresAt)
    ShareState.set(context, share)
    LocationSharingService.start(context, share)
    sendShareLink(context, share)
}

private fun randomToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun sendShareLink(context: Context, share: ActiveShare) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Follow my live location “${share.name}”: ${share.url}")
    context.startActivity(Intent.createChooser(send, "Share location link"))
}

@Composable
private fun ShareDialog(onDismiss: () -> Unit, onConfirm: (String, Long) -> Unit) {
    var name by remember { mutableStateOf("My location") }
    var idx by remember { mutableIntStateOf(DEFAULT_DURATION_INDEX) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share Location") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "Duration: ${DURATION_CHOICES[idx].second}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = idx.toFloat(),
                    onValueChange = { idx = it.roundToInt() },
                    valueRange = 0f..(DURATION_CHOICES.size - 1).toFloat(),
                    steps = DURATION_CHOICES.size - 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), DURATION_CHOICES[idx].first) },
            ) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ActiveShareCard(share: ActiveShare, onStop: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(share) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val statusText = if (share.expiresAt == 0L) {
        "Sharing until you stop"
    } else {
        val remaining = share.expiresAt - now
        if (remaining <= 0) "Ended" else "Ends in ${formatRemaining(remaining)}"
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Sharing: ${share.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                share.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(share.url)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                OutlinedButton(onClick = { sendShareLink(context, share) }) {
                    Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSec = ms / 1_000
    val d = totalSec / 86_400
    val h = (totalSec % 86_400) / 3_600
    val m = (totalSec % 3_600) / 60
    val s = totalSec % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}
