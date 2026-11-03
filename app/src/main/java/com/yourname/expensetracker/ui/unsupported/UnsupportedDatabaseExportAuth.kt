package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Longest time, in milliseconds, the export gate waits for the stored lock setting (app-lock S21-c, owner decision 73a). */
internal const val EXPORT_AUTH_READ_TIMEOUT_MILLIS: Long = 2_000L

/**
 * Longest time, in milliseconds, the export authentication prompt may stay open (S22-b, F6-ii) before the screen cancels
 * it and ends the attempt as denied. It equals the lock controller's open-origin prompt watchdog (5 minutes); this
 * screen has no controller, so the value is repeated here and a parity test pins the two together.
 */
internal const val EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS: Long = 5 * 60_000L

/**
 * What the export gate found in the app-lock store. [UNREADABLE] covers an unreadable store, a read that
 * threw, a read that did not finish within [EXPORT_AUTH_READ_TIMEOUT_MILLIS] and an entry point that could not
 * be resolved. It is never read as "off".
 */
internal enum class ExportLockRead { ON, OFF, UNREADABLE }

/**
 * The export gate's answer (G-LOCK-1, fix-1.0.4 D1). [Required]: the device prompt must pass before the picker opens.
 * [NotRequired]: the picker may open at once. [Denied]: the lock is on (or unknown) in a mode the device cannot
 * authenticate in, so nothing can be asked and the export does not start; the activity shows its existing constant
 * denied message.
 */
internal enum class ExportAuthDecision { Required, NotRequired, Denied }

/**
 * The one narrow, read-only window the unsupported-database screen has into the app-lock feature
 * (app-lock S21-c, #10).
 *
 * It exposes exactly two bindings: the lock settings reader and the capability port. Their implementations
 * depend on the application context only (a dedicated preferences store and the platform biometric manager).
 * Nothing Room-backed is reachable from here, because the screen exists precisely because the live database
 * cannot be opened. Do not add a member to this interface that is not a read of the lock or of the capability.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UnsupportedDatabaseExportAuthEntryPoint {
    fun appLockSettingsRepository(): AppLockSettingsRepository
    fun deviceAuthCapabilityProvider(): DeviceAuthCapabilityProvider
}

/**
 * Decides whether the user must pass device authentication before the database file is exported (S21-c).
 * [isAuthRequired] resolves the entry point; [decide] is the same decision over already resolved ports.
 *
 * Rule (G-LOCK-1, fix-1.0.4 D1 as revised). The stored lock mode is not read or used:
 *  - Lock OFF: [ExportAuthDecision.NotRequired]; the capability is not read.
 *  - Lock ON, or the store cannot be read within the bound (fail closed): capability AVAILABLE, CHECK_FAILED or no
 *    answer is [ExportAuthDecision.Required] (CHECK_FAILED is transient, as in the lock controller); any other
 *    capability (UNSUPPORTED_API, NO_CREDENTIAL, HW_UNAVAILABLE, UNKNOWN) is [ExportAuthDecision.Denied].
 *
 * This screen has no PIN prompt and no PIN binding, so the device prompt stands in for the App PIN. A user with the
 * lock on and no working device prompt cannot export here until they set a screen lock; nothing is deleted.
 *
 * Every failure of a read maps to the stricter answer. Nothing here records a diagnostic and no exception
 * text, path, PIN or setting value leaves this object; the result is one of three enum values.
 */
internal object UnsupportedDatabaseExportAuth {

    /** Resolves the entry point and answers [decide]. Must be called from a coroutine. */
    suspend fun isAuthRequired(context: Context): ExportAuthDecision {
        val resolved: Pair<AppLockSettingsRepository, DeviceAuthCapabilityProvider>? = try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                UnsupportedDatabaseExportAuthEntryPoint::class.java
            )
            Pair(entryPoint.appLockSettingsRepository(), entryPoint.deviceAuthCapabilityProvider())
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Bounded: no exception text is kept. Nothing could be read, so the store counts as unreadable.
            null
        }
        return decide(resolved?.first, resolved?.second)
    }

    /** The decision over already resolved ports. A null port counts as unreadable (lock) or failed (capability). */
    suspend fun decide(
        repository: AppLockSettingsRepository?,
        capabilityProvider: DeviceAuthCapabilityProvider?,
        timeoutMillis: Long = EXPORT_AUTH_READ_TIMEOUT_MILLIS
    ): ExportAuthDecision {
        if (readLock(repository, timeoutMillis) == ExportLockRead.OFF) return ExportAuthDecision.NotRequired
        val capability = readCapability(capabilityProvider)
        // The lock is on or unknown. A capability that cannot run a prompt is denied in every mode: nothing can be asked.
        return if (capabilityRequiresAuth(capability)) ExportAuthDecision.Required else ExportAuthDecision.Denied
    }

    /** One fresh read bounded by [timeoutMillis]. A timeout, a thrown read and an unreadable store are all [ExportLockRead.UNREADABLE]. */
    suspend fun readLock(repository: AppLockSettingsRepository?, timeoutMillis: Long): ExportLockRead {
        if (repository == null) return ExportLockRead.UNREADABLE
        val state: AppLockSettingsLoadState? = try {
            withTimeoutOrNull(timeoutMillis) { repository.getLoadState() }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Bounded: the exception text is dropped; an unreadable store keeps the export gated.
            null
        }
        return when (state) {
            is AppLockSettingsLoadState.Loaded -> lockReadOf(state.settings.appLockEnabled)
            is AppLockSettingsLoadState.FirstRunDefault -> lockReadOf(state.settings.appLockEnabled)
            is AppLockSettingsLoadState.Unreadable -> ExportLockRead.UNREADABLE
            null -> ExportLockRead.UNREADABLE
        }
    }

    private fun lockReadOf(lockEnabled: Boolean): ExportLockRead =
        if (lockEnabled) ExportLockRead.ON else ExportLockRead.OFF

    /** One fresh capability evaluation (never cached). Null means the check could not be made. */
    fun readCapability(capabilityProvider: DeviceAuthCapabilityProvider?): DeviceAuthCapability? =
        try {
            capabilityProvider?.current()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }

    /**
     * Whether the device can (or may be able to) run the prompt. Null (no answer) is treated like CHECK_FAILED.
     * A false answer means [decide] denies the export when the lock is on or unknown.
     */
    fun capabilityRequiresAuth(capability: DeviceAuthCapability?): Boolean =
        when (capability) {
            null,
            DeviceAuthCapability.AVAILABLE,
            DeviceAuthCapability.CHECK_FAILED -> true
            DeviceAuthCapability.UNSUPPORTED_API,
            DeviceAuthCapability.NO_CREDENTIAL,
            DeviceAuthCapability.HW_UNAVAILABLE,
            DeviceAuthCapability.UNKNOWN -> false
        }
}
