package com.yourname.expensetracker.data.privacy.applock

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only authenticator combination the app lock ever uses (INV-23). `BIOMETRIC_WEAK` is never
 * requested. The prompt adapter in `ui/applock` repeats this value and a test pins both.
 */
internal const val APP_LOCK_ALLOWED_AUTHENTICATORS: Int = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

private const val MIN_SUPPORTED_SDK = Build.VERSION_CODES.R

/**
 * Evaluates [DeviceAuthCapability] at call time (INV-23, AMD-3). Nothing is cached.
 *
 * API below 30 is [DeviceAuthCapability.UNSUPPORTED_API] without asking the library, because the
 * strong-or-credential combination is unsupported there (AE-34). A failure of the check itself maps
 * to [DeviceAuthCapability.UNKNOWN], which refuses the enable (fail closed).
 */
class AndroidDeviceAuthCapabilityProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceAuthCapabilityProvider {

    override fun current(): DeviceAuthCapability {
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) return DeviceAuthCapability.UNSUPPORTED_API
        return try {
            mapCanAuthenticate(
                BiometricManager.from(context).canAuthenticate(APP_LOCK_ALLOWED_AUTHENTICATORS)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: RuntimeException) {
            // Bounded: the exception text is dropped, the capability becomes UNKNOWN (fail closed).
            DeviceAuthCapability.UNKNOWN
        }
    }

    internal companion object {
        /** Maps a `canAuthenticate` result to the bounded capability. Unknown codes are [DeviceAuthCapability.UNKNOWN]. */
        @VisibleForTesting
        fun mapCanAuthenticate(code: Int): DeviceAuthCapability = when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> DeviceAuthCapability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> DeviceAuthCapability.NO_CREDENTIAL
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> DeviceAuthCapability.HW_UNAVAILABLE
            else -> DeviceAuthCapability.UNKNOWN
        }
    }
}
