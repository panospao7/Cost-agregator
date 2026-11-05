package com.yourname.expensetracker.data.backup

import android.content.Context
import com.yourname.expensetracker.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UF-46 (INV-17): one-shot "restore was not verifiable" notice that survives the post-restore
 * process restart.
 *
 * The restore screen's in-screen card dies with the process relaunch, so the outcome is persisted
 * as a single boolean (no counts, names, paths or any other data; not stored in the database) and
 * consumed exactly once by the main screen after the relaunch.
 *
 * Follows the repo's existing SharedPreferences pattern for small UI/runtime flags (see
 * NotificationPromptStore). [record] is best-effort: a failure is logged with a bounded
 * class-name-only message and never throws (cancellation is always rethrown).
 */
@Singleton
class RestoreVerificationNoticeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val prefs
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persists whether the just-completed restore was NOT_VERIFIABLE. `false` clears a stale flag.
     * Written with a synchronous commit() so the value is durable before this call returns
     * (the caller requests the process restart only afterwards).
     */
    suspend fun record(pending: Boolean) {
        try {
            withContext(ioDispatcher) {
                val committed = prefs.edit().putBoolean(KEY_PENDING, pending).commit()
                if (!committed) {
                    Timber.w("RESTORE_NOTICE_RECORD_FAILED reason=commit_false")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("RESTORE_NOTICE_RECORD_FAILED class=%s", e.javaClass.simpleName)
        }
    }

    /** Returns the recorded flag and removes it (one-shot); false when nothing was recorded. */
    suspend fun consume(): Boolean = try {
        withContext(ioDispatcher) {
            val preferences = prefs
            val pending = preferences.getBoolean(KEY_PENDING, false)
            if (preferences.contains(KEY_PENDING)) {
                preferences.edit().remove(KEY_PENDING).commit()
            }
            pending
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("RESTORE_NOTICE_CONSUME_FAILED class=%s", e.javaClass.simpleName)
        false
    }

    /**
     * J-11-2: persists the one-shot "notifications captured on another phone may not be readable"
     * flag (a single boolean, no content) before the post-restore restart is requested, so the
     * relaunched process can show it once. Same durability and failure contract as [record].
     */
    suspend fun recordNotificationUnreadable(pending: Boolean) {
        try {
            withContext(ioDispatcher) {
                val committed = prefs.edit().putBoolean(KEY_NOTIFICATION_UNREADABLE_PENDING, pending).commit()
                if (!committed) {
                    Timber.w("RESTORE_NOTICE_RECORD_FAILED reason=commit_false")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("RESTORE_NOTICE_RECORD_FAILED class=%s", e.javaClass.simpleName)
        }
    }

    /** Returns the recorded notification-unreadable flag and removes it (one-shot); false when none. */
    suspend fun consumeNotificationUnreadable(): Boolean = try {
        withContext(ioDispatcher) {
            val preferences = prefs
            val pending = preferences.getBoolean(KEY_NOTIFICATION_UNREADABLE_PENDING, false)
            if (preferences.contains(KEY_NOTIFICATION_UNREADABLE_PENDING)) {
                preferences.edit().remove(KEY_NOTIFICATION_UNREADABLE_PENDING).commit()
            }
            pending
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("RESTORE_NOTICE_CONSUME_FAILED class=%s", e.javaClass.simpleName)
        false
    }

    companion object {
        const val PREFS_NAME = "restore_verification_notice"
        const val KEY_PENDING = "restore_not_verifiable_pending"
        const val KEY_NOTIFICATION_UNREADABLE_PENDING = "restore_notification_unreadable_pending"
    }
}
