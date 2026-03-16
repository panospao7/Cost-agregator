package com.yourname.expensetracker.data.ai.provider

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.OnDeviceModelStatus
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Environment monitor that queries real ML Kit GenAI Prompt API status.
 */
@Singleton
class DefaultAiEnvironmentMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) : AiEnvironmentMonitor {

    /**
     * Cached ML Kit feature status.  Initialised to `null` (never queried).
     * The first call to [getOnDeviceModelStatus] on a device below API 34
     * short-circuits before touching this field.
     */
    private val cachedFeatureStatus = AtomicReference<OnDeviceModelStatus>(null)

    override fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun isWifiConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override suspend fun getOnDeviceModelStatus(capability: AiCapability): OnDeviceModelStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return OnDeviceModelStatus.UNSUPPORTED_ANDROID_VERSION
        }

        try {
            val model: GenerativeModel = Generation.getClient()
            val featureStatus: Int = model.checkStatus()
            val mapped = mapFeatureStatus(featureStatus)
            cachedFeatureStatus.set(mapped)
            Timber.d("DefaultAiEnvironmentMonitor: ML Kit status=%d → %s", featureStatus, mapped)
            return mapped
        } catch (e: GenAiException) {
            Timber.w(e, "DefaultAiEnvironmentMonitor: checkStatus failed (code=%d)", e.errorCode)
            val mapped = cachedFeatureStatus.get() ?: OnDeviceModelStatus.UNAVAILABLE
            cachedFeatureStatus.set(mapped)
            return mapped
        } catch (e: Exception) {
            Timber.w(e, "DefaultAiEnvironmentMonitor: checkStatus failed unexpectedly")
            val mapped = cachedFeatureStatus.get() ?: OnDeviceModelStatus.UNKNOWN
            cachedFeatureStatus.set(mapped)
            return mapped
        }
    }

    private fun mapFeatureStatus(status: Int): OnDeviceModelStatus {
        return when (status) {
            FeatureStatus.AVAILABLE -> OnDeviceModelStatus.AVAILABLE
            FeatureStatus.DOWNLOADABLE -> OnDeviceModelStatus.NOT_INSTALLED
            FeatureStatus.DOWNLOADING -> OnDeviceModelStatus.DOWNLOADING
            FeatureStatus.UNAVAILABLE -> OnDeviceModelStatus.UNAVAILABLE
            else -> OnDeviceModelStatus.UNKNOWN
        }
    }
}
