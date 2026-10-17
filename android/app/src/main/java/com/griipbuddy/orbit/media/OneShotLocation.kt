package com.griipbuddy.orbit.media

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One location fix for sharing it once (2026-10-17, docs/RICH_MESSAGES.md). Uses the phone's own location providers
 * (no Google Play services); nothing keeps running afterwards, and there is no background or live location.
 * The caller must already hold ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION.
 */
object OneShotLocation {
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 20_000): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null
        // A recent fix (under two minutes old) is good enough and instant.
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 120_000 }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
        if (recent != null && recent.hasAccuracy() && recent.accuracy <= 50f) return recent
        val fresh = withTimeoutOrNull(timeoutMs) { fix(context, lm, providers.first { it != LocationManager.PASSIVE_PROVIDER || providers.size == 1 }) }
        return fresh ?: recent
    }

    @SuppressLint("MissingPermission")
    private suspend fun fix(context: Context, lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { if (cont.isActive) cont.resume(it) }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }
}
