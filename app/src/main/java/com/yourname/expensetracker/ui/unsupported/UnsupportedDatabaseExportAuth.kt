package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider
import com.yourname.expensetracker.domain.privacy.applock.LockMode
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
 * One bounded read of the lock: whether it is on, and the stored lock mode. [mode] is null whenever the store was
 * not read (unreadable, timed out, threw, or no repository), and that unknown mode is treated like APP_PIN.
 */
internal data class ExportLockSnapshot(val lock: ExportLockRead, val mode: LockMode?)

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
 * Rule (G-LOCK-1, fix-1.0.4 D1). The lock OFF is [ExportAuthDecision.NotRequired], whatever the mode. Otherwise
 * (the committed lock is on, or the store cannot be read within the bound: fail closed) the stored lock mode
 * decides how the capability is read:
 *  - Mode DEVICE_AUTH (read from the store): the device-credential rule. Capability AVAILABLE, CHECK_FAILED or no
 *    answer is [ExportAuthDecision.Required] (CHECK_FAILED is transient, as in the lock controller). Any other
 *    capability (UNSUPPORTED_API, NO_CREDENTIAL, HW_UNAVAILABLE, UNKNOWN) leaves the lock inert on this device, as
 *    for the main screen, so it is [ExportAuthDecision.NotRequired].
 *  - Mode APP_PIN, or unknown (the store was not read, so the mode was not either): a PIN lock is never inert
 *    (ux-flows.md:211, T6 "no verifier"), but this screen has no PIN prompt and no PIN binding. Capability
 *    AVAILABLE, CHECK_FAILED or no answer is [ExportAuthDecision.Required] (the device prompt); any other
 *    capability is [ExportAuthDecision.Denied] (fail closed). A PIN-only user cannot use this rescue export
 *    until a PIN prompt exists here; nothing is deleted.
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
        val snapshot = readLockSnapshot(repository, timeoutMillis)
        if (snapshot.lock == ExportLockRead.OFF) return ExportAuthDecision.NotRequired
        val capability = readCapability(capabilityProvider)
        if (capabilityRequiresAuth(capability)) return ExportAuthDecision.Required
        // The capability cannot run a prompt. Only a mode read as DEVICE_AUTH leaves the lock inert; APP_PIN and an
        // unknown mode are never inert, and there is no PIN prompt here, so the export is denied.
        return if (snapshot.mode == LockMode.DEVICE_AUTH) ExportAuthDecision.NotRequired else ExportAuthDecision.Denied
    }

    /** One fresh read bounded by [timeoutMillis]. A timeout, a thrown read and an unreadable store are all [ExportLockRead.UNREADABLE]. */
    suspend fun readLock(repository: AppLockSettingsRepository?, timeoutMillis: Long): ExportLockRead =
        readLockSnapshot(repository, timeoutMillis).lock

    /** [readLock] plus the stored lock mode. The mode is null whenever the lock read is [ExportLockRead.UNREADABLE]. */
    suspend fun readLockSnapshot(repository: AppLockSettingsRepository?, timeoutMillis: Long): ExportLockSnapshot {
        if (repository == null) return ExportLockSnapshot(ExportLockRead.UNREADABLE, null)
        val state: AppLockSettingsLoadState? = try {
            withTimeoutOrNull(timeoutMillis) { repository.getLoadState() }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Bounded: the exception text is dropped; an unreadable store keeps the export gated.
            null
        }
        return when (state) {
            is AppLockSettingsLoadState.Loaded -> snapshotOf(state.settings.appLockEnabled, state.settings.lockMode)
            is AppLockSettingsLoadState.FirstRunDefault ->
                snapshotOf(state.settings.appLockEnabled, state.settings.lockMode)
            is AppLockSettingsLoadState.Unreadable -> ExportLockSnapshot(ExportLockRead.UNREADABLE, null)
            null -> ExportLockSnapshot(ExportLockRead.UNREADABLE, null)
        }
    }

    private fun snapshotOf(lockEnabled: Boolean, mode: LockMode): ExportLockSnapshot =
        ExportLockSnapshot(if (lockEnabled) ExportLockRead.ON else ExportLockRead.OFF, mode)

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
     * This is the capability half only; [decide] adds the lock mode for a capability that returns false.
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
