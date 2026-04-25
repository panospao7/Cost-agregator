package com.yourname.expensetracker.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.yourname.expensetracker.domain.location.ForegroundLocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of [ForegroundLocationProvider].
 *
 * Uses Fused Location Provider (Google Play Services) for a single
 * current-location fix.  Falls back to last-known location if a fresh fix
 * cannot be obtained quickly.
 *
 * Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission —
 * checked before calling; returns null if not granted.
 *
 * NOTE: This uses `kotlinx-coroutines-play-services` (`await()` on Tasks).
 * If that dependency is absent, replace with a suspendCancellableCoroutine
 * wrapper.
 */
@Singleton
class AndroidForegroundLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : ForegroundLocationProvider {

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    override suspend fun getLastKnownLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        return try {
            val cts = CancellationTokenSource()
            val loc: Location? = fusedClient
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .await()
            loc?.toLatLon() ?: fusedClient.lastLocation.await()?.toLatLon()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked mid-call: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Location unavailable: ${e.message}")
            try {
                fusedClient.lastLocation.await()?.toLatLon()
            } catch (fallback: CancellationException) {
                throw fallback
            } catch (fallback: SecurityException) {
                Log.w(TAG, "Cached location unavailable after permission loss: ${fallback.message}")
                null
            } catch (fallback: Exception) {
                Log.w(TAG, "Cached location unavailable: ${fallback.message}")
                null
            }
        }
    }

    private fun Location.toLatLon(): Pair<Double, Double> = latitude to longitude

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "AndroidForegroundLocationProvider"
    }
}
