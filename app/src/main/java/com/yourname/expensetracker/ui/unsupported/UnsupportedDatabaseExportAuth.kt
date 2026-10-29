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
 * Rule: authentication is required when the committed lock is on, or when the lock store cannot be read
 * within the bound (fail closed), AND the device can run the prompt (capability AVAILABLE) or the capability
 * check itself failed (CHECK_FAILED, treated as transient exactly as the lock controller treats it).
 * For any other capability the lock is inert on this device (INERT in the lock state machine: the app opens
 * without a prompt), so nothing can be asked and the export is not gated: UNSUPPORTED_API, NO_CREDENTIAL,
 * HW_UNAVAILABLE and UNKNOWN. This matches what the lock does for the main screen in the same states.
 *
 * Capability rule, Inc 2 note: this table is the Inc 1 (device-credential lock) rule only. In Inc 2 a PIN-mode
 * lock is never INERT (ux-flows.md:211, T6 "no verifier"): the PIN verifier does not depend on the device
 * credential. When a lock mode exists, [capabilityRequiresAuth] must therefore require authentication for
 * NO_CREDENTIAL (and for every other capability) whenever the mode is PIN. Until the lock mode is readable
 * here, this file must not be read as "NO_CREDENTIAL is never gated".
 *
 * Every failure of a read maps to the stricter answer. Nothing here records a diagnostic and no exception
 * text, path or setting value leaves this object; the result is a single boolean.
 */
internal object UnsupportedDatabaseExportAuth {

    /** Resolves the entry point and answers [isAuthRequired]. Must be called from a coroutine. */
    suspend fun isAuthRequired(context: Context): Boolean {
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
    ): Boolean {
        if (readLock(repository, timeoutMillis) == ExportLockRead.OFF) return false
        return capabilityRequiresAuth(readCapability(capabilityProvider))
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
            is AppLockSettingsLoadState.Loaded ->
                if (state.settings.appLockEnabled) ExportLockRead.ON else ExportLockRead.OFF
            is AppLockSettingsLoadState.FirstRunDefault ->
                if (state.settings.appLockEnabled) ExportLockRead.ON else ExportLockRead.OFF
            is AppLockSettingsLoadState.Unreadable -> ExportLockRead.UNREADABLE
            null -> ExportLockRead.UNREADABLE
        }
    }

    /** One fresh capability evaluation (never cached). Null means the check could not be made. */
    fun readCapability(capabilityProvider: DeviceAuthCapabilityProvider?): DeviceAuthCapability? =
        try {
            capabilityProvider?.current()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            null
        }

    /** The capability half of the rule. Null (no answer) is treated like CHECK_FAILED. */
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
