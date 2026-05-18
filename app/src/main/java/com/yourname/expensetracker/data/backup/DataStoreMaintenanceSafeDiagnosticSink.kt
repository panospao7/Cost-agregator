package com.yourname.expensetracker.data.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.maintenanceDiagnosticsStore by preferencesDataStore("maintenance_diagnostics")

private val KEY_RECORDS = stringPreferencesKey("records")
private const val MAX_RECORDS = 200

data class MaintenanceDiagnosticRecord(
    val id: String,
    val operation: String,
    val mode: String,
    val pipeline: String?,
    val entity: String?,
    val reason: String,
    val timestamp: Long
)

/**
 * DataStore-backed ring-buffer implementation of [MaintenanceSafeDiagnosticSink].
 * Survives process death. Bounded to [MAX_RECORDS] entries.
 * [recordBlockedOperation] is suspend — it awaits DataStore commit before returning.
 */
@Singleton
class DataStoreMaintenanceSafeDiagnosticSink @Inject constructor(
    @ApplicationContext private val context: Context
) : MaintenanceSafeDiagnosticSink {

    override suspend fun recordBlockedOperation(
        operation: String,
        mode: RestoreMaintenanceMode.Mode,
        pipeline: String?,
        entity: String?,
        reason: MaintenanceBlockedReason
    ) {
        Timber.w("BLOCKED[%s/%s] op=%s pipeline=%s entity=%s",
            mode.label, reason, operation, pipeline ?: "-", entity ?: "-")
        try {
            context.maintenanceDiagnosticsStore.edit { prefs ->
                val existing = prefs[KEY_RECORDS]?.let { parseRecords(it) } ?: mutableListOf()
                existing.add(
                    MaintenanceDiagnosticRecord(
                        id = UUID.randomUUID().toString(),
                        operation = operation,
                        mode = mode.label,
                        pipeline = pipeline,
                        entity = entity,
                        reason = reason.name,
                        timestamp = System.currentTimeMillis()
                    )
                )
                val trimmed = if (existing.size > MAX_RECORDS) existing.takeLast(MAX_RECORDS) else existing
                prefs[KEY_RECORDS] = serializeRecords(trimmed)
            }
        } catch (e: Exception) {
            Timber.w(e, "DataStoreMaintenanceSafeDiagnosticSink: failed to persist record")
        }
    }

    override fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>> =
        context.maintenanceDiagnosticsStore.data.map { prefs ->
            prefs[KEY_RECORDS]?.let { parseRecords(it) } ?: emptyList()
        }

    override suspend fun clearOlderThan(cutoffMs: Long) {
        context.maintenanceDiagnosticsStore.edit { prefs ->
            val existing = prefs[KEY_RECORDS]?.let { parseRecords(it) } ?: return@edit
            prefs[KEY_RECORDS] = serializeRecords(existing.filter { it.timestamp >= cutoffMs })
        }
    }

    private fun serializeRecords(records: List<MaintenanceDiagnosticRecord>): String {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("op", r.operation); put("mode", r.mode)
                if (r.pipeline != null) put("pipeline", r.pipeline)
                if (r.entity != null) put("entity", r.entity)
                put("reason", r.reason); put("ts", r.timestamp)
            })
        }
        return arr.toString()
    }

    private fun parseRecords(json: String): MutableList<MaintenanceDiagnosticRecord> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MaintenanceDiagnosticRecord(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    operation = o.getString("op"),
                    mode = o.getString("mode"),
                    pipeline = o.optString("pipeline").takeIf { it.isNotEmpty() },
                    entity = o.optString("entity").takeIf { it.isNotEmpty() },
                    reason = o.optString("reason", "UNKNOWN"),
                    timestamp = o.getLong("ts")
                )
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }
}
