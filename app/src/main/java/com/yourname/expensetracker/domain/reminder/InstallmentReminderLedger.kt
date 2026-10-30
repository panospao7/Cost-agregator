package com.yourname.expensetracker.domain.reminder

import android.content.Context
import android.content.SharedPreferences
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local two-phase delivery ledger for installment reminders (S7b, INV-15, owner-approvals item 44).
 * One opaque key per `planUuid:seq:recordGeneration`. A record is `dueDay|claimedAt|sent`:
 * integers and a flag only, no plan name, amount or lender. It is not part of the Room schema and
 * is never exported.
 *
 * Phase 1, [tryClaim]: records the key as unsent with the claim time. Phase 2, [markSent]: finalizes
 * it after the notification was posted. This mirrors the recurring `recoverStaleClaimedDeliveries`:
 * an unsent claim older than [STALE_CLAIM_THRESHOLD_MS] (a run that died between claim and post)
 * can be claimed again; a sent key is never claimed again. A record for a different due day than
 * the current entry's never suppresses (an E3 date edit does not bump `recordGeneration`).
 */
interface InstallmentReminderLedger {
    /**
     * Atomically claims [key] for the entry due on [dueEpochDay]. True only when the claim was
     * obtained AND persisted: the key is absent, or its record is for another due day, or it is
     * unsent and older than [STALE_CLAIM_THRESHOLD_MS]. A sent key, a fresh unsent claim, an
     * unreadable record, or a failed write all return false (fail closed, nothing is posted).
     * Also prunes records whose due day is more than [RETENTION_DAYS] before [todayEpochDay].
     */
    suspend fun tryClaim(key: String, dueEpochDay: Long, todayEpochDay: Long, nowMillis: Long): Boolean

    /**
     * Finalizes [key] as sent for [dueEpochDay] (phase 2); creates the record when it is absent.
     * Returns false when the write failed.
     */
    suspend fun markSent(key: String, dueEpochDay: Long, nowMillis: Long): Boolean

    /**
     * True when a reminder for [key] and [dueEpochDay] must not be listed now: it was sent, or an
     * unsent claim is still fresh, or the record is unreadable. False when absent, recorded for a
     * different due day, or an unsent claim that is stale.
     */
    suspend fun isClaimed(key: String, dueEpochDay: Long, nowMillis: Long): Boolean

    /**
     * Gives an UNSENT claim back so a later run may remind again. A sent key is never released.
     */
    suspend fun release(key: String)

    /** Removes records whose due day is more than [RETENTION_DAYS] before [todayEpochDay]. */
    suspend fun prune(todayEpochDay: Long)

    companion object {
        /** Same threshold as the recurring reminder claim recovery (5 minutes). */
        const val STALE_CLAIM_THRESHOLD_MS: Long = RecurringLifecycleCoordinator.STALE_CLAIM_THRESHOLD_MS

        /** Records whose due day is older than this many days are forgotten (the entry can no longer project). */
        const val RETENTION_DAYS: Long = 60L
    }
}

@Singleton
class InstallmentReminderLedgerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : InstallmentReminderLedger {

    private val lock = Any()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Decoded record. [dueDay] and [claimedAt] are epoch day / epoch millis; [sent] is phase 2. */
    private data class Record(val dueDay: Long, val claimedAt: Long, val sent: Boolean) {
        fun encode(): String = "$dueDay$SEP$claimedAt$SEP${if (sent) 1 else 0}"

        // Never print: redacted like every other reminder value.
        override fun toString(): String = "Record(redacted)"
    }

    private sealed interface Lookup {
        object Absent : Lookup
        object Unreadable : Lookup
        data class Found(val record: Record) : Lookup
    }

    override suspend fun tryClaim(
        key: String,
        dueEpochDay: Long,
        todayEpochDay: Long,
        nowMillis: Long
    ): Boolean = synchronized(lock) {
        val p = prefs
        val prefKey = PREFIX + key
        val editor = p.edit()
        removeExpired(p, editor, todayEpochDay)
        when (val existing = lookup(p, prefKey)) {
            Lookup.Absent -> Unit
            Lookup.Unreadable -> return@synchronized false
            is Lookup.Found -> {
                if (blocks(existing.record, dueEpochDay, nowMillis)) {
                    // Still commit the prune; the claim itself is denied.
                    editor.commit()
                    return@synchronized false
                }
            }
        }
        editor.putString(prefKey, Record(dueEpochDay, nowMillis, sent = false).encode())
        // commit() false = the claim is not durable: fail closed, the caller must not post.
        editor.commit()
    }

    override suspend fun markSent(key: String, dueEpochDay: Long, nowMillis: Long): Boolean =
        synchronized(lock) {
            val p = prefs
            val prefKey = PREFIX + key
            val claimedAt = (lookup(p, prefKey) as? Lookup.Found)?.record?.claimedAt ?: nowMillis
            p.edit().putString(prefKey, Record(dueEpochDay, claimedAt, sent = true).encode()).commit()
        }

    override suspend fun isClaimed(key: String, dueEpochDay: Long, nowMillis: Long): Boolean =
        synchronized(lock) {
            when (val existing = lookup(prefs, PREFIX + key)) {
                Lookup.Absent -> false
                Lookup.Unreadable -> true
                is Lookup.Found -> blocks(existing.record, dueEpochDay, nowMillis)
            }
        }

    override suspend fun release(key: String) {
        synchronized(lock) {
            val p = prefs
            val prefKey = PREFIX + key
            // Only an unsent claim is given back; a sent key (or an unreadable record) stays.
            val existing = lookup(p, prefKey)
            if (existing is Lookup.Found && !existing.record.sent) {
                p.edit().remove(prefKey).commit()
            }
        }
    }

    override suspend fun prune(todayEpochDay: Long) {
        synchronized(lock) {
            val p = prefs
            val editor = p.edit()
            if (removeExpired(p, editor, todayEpochDay) > 0) editor.commit()
        }
    }

    private fun blocks(record: Record, dueEpochDay: Long, nowMillis: Long): Boolean {
        // A record for another due day belongs to a date the entry no longer has (E3 edit).
        if (record.dueDay != dueEpochDay) return false
        if (record.sent) return true
        return nowMillis - record.claimedAt < InstallmentReminderLedger.STALE_CLAIM_THRESHOLD_MS
    }

    /** Queues removal of expired records on [editor]; returns how many were queued. */
    private fun removeExpired(p: SharedPreferences, editor: SharedPreferences.Editor, todayEpochDay: Long): Int {
        var removed = 0
        for ((existingKey, value) in p.all) {
            if (!existingKey.startsWith(PREFIX)) continue
            val record = decode(value) ?: continue
            if (record.dueDay < todayEpochDay - InstallmentReminderLedger.RETENTION_DAYS) {
                editor.remove(existingKey)
                removed++
            }
        }
        return removed
    }

    private fun lookup(p: SharedPreferences, prefKey: String): Lookup {
        if (!p.contains(prefKey)) return Lookup.Absent
        val record = decode(p.all[prefKey]) ?: return Lookup.Unreadable
        return Lookup.Found(record)
    }

    private fun decode(value: Any?): Record? {
        val text = value as? String ?: return null
        val parts = text.split(SEP)
        if (parts.size != 3) return null
        val dueDay = parts[0].toLongOrNull() ?: return null
        val claimedAt = parts[1].toLongOrNull() ?: return null
        val sent = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        return Record(dueDay, claimedAt, sent)
    }

    private companion object {
        const val PREFS_NAME = "installment_reminder_ledger"
        const val PREFIX = "k:"
        const val SEP = '|'
    }
}
