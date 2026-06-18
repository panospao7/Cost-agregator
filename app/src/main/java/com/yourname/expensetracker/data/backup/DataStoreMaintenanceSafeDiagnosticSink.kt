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
    val timestamp: Long,
    // Full diagnostic event fields (null for legacy blocked-operation records)
    val correlationId: String? = null,
    val causationId: String? = null,
    val outcome: String? = null,
    val severity: String? = null,
    val reasonCode: String? = null,
    val entityId: Long? = null,
    val sourceType: String? = null,
    val sourceIdHash: String? = null,
    val elapsedMs: Long? = null,
    val metadataJson: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessageSafe: String? = null,
    val writeFailureClass: String? = null,
    val writeFailureMessageSafe: String? = null,
    val isTerminal: Boolean = false
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

    override suspend fun recordDiagnosticEvent(
        event: com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent,
        mode: RestoreMaintenanceMode.Mode,
        writeFailure: Throwable?
    ) {
        Timber.w("SAFE_SINK[%s] pipeline=%s stage=%s outcome=%s corr=%s terminal=%b",
            mode.label, event.pipeline.name, event.stage, event.outcome.name,
            event.correlationId.take(8), event.isTerminal)
        try {
            val sanitizer = com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer()
            context.maintenanceDiagnosticsStore.edit { prefs ->
                val existing = prefs[KEY_RECORDS]?.let { parseRecords(it) } ?: mutableListOf()
                existing.add(
                    MaintenanceDiagnosticRecord(
                        id = UUID.randomUUID().toString(),
                        operation = "${event.pipeline.name}.${event.stage}",
                        mode = mode.label,
                        pipeline = event.pipeline.name,
                        entity = event.entityType,
                        reason = event.reasonCode?.name ?: "NONE",
                        timestamp = System.currentTimeMillis(),
                        correlationId = event.correlationId,
                        causationId = event.causationId,
                        outcome = event.outcome.name,
                        severity = event.severity.name,
                        reasonCode = event.reasonCode?.name,
                        entityId = event.entityId,
                        sourceType = event.sourceType,
                        sourceIdHash = event.sourceIdHash,
                        elapsedMs = event.elapsedMs,
                        metadataJson = sanitizer.sanitizeJsonString(
                            if (event.metadata.isEmpty()) null else event.metadata.toJson()
                        ),
                        exceptionClass = event.exception?.javaClass?.simpleName,
                        exceptionMessageSafe = sanitizer.sanitizeExceptionMessage(event.exception?.message),
                        writeFailureClass = writeFailure?.javaClass?.simpleName,
                        writeFailureMessageSafe = sanitizer.sanitizeExceptionMessage(writeFailure?.message),
                        isTerminal = event.isTerminal
                    )
                )
                val trimmed = if (existing.size > MAX_RECORDS) existing.takeLast(MAX_RECORDS) else existing
                prefs[KEY_RECORDS] = serializeRecords(trimmed)
            }
        } catch (e: Exception) {
            Timber.w(e, "DataStoreMaintenanceSafeDiagnosticSink: failed to persist diagnostic event")
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
                if (r.correlationId != null) put("corrId", r.correlationId)
                if (r.causationId != null) put("causId", r.causationId)
                if (r.outcome != null) put("outcome", r.outcome)
                if (r.severity != null) put("severity", r.severity)
                if (r.reasonCode != null) put("reasonCode", r.reasonCode)
                if (r.entityId != null) put("entityId", r.entityId)
                if (r.sourceType != null) put("sourceType", r.sourceType)
                if (r.sourceIdHash != null) put("sourceIdHash", r.sourceIdHash)
                if (r.elapsedMs != null) put("elapsedMs", r.elapsedMs)
                if (r.metadataJson != null) put("meta", r.metadataJson)
                if (r.exceptionClass != null) put("excClass", r.exceptionClass)
                if (r.exceptionMessageSafe != null) put("excMsg", r.exceptionMessageSafe)
                if (r.writeFailureClass != null) put("wfClass", r.writeFailureClass)
                if (r.writeFailureMessageSafe != null) put("wfMsg", r.writeFailureMessageSafe)
                put("terminal", r.isTerminal)
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
                    timestamp = o.getLong("ts"),
                    correlationId = o.optString("corrId").takeIf { it.isNotEmpty() },
                    causationId = o.optString("causId").takeIf { it.isNotEmpty() },
                    outcome = o.optString("outcome").takeIf { it.isNotEmpty() },
                    severity = o.optString("severity").takeIf { it.isNotEmpty() },
                    reasonCode = o.optString("reasonCode").takeIf { it.isNotEmpty() },
                    entityId = if (o.has("entityId")) o.getLong("entityId") else null,
                    sourceType = o.optString("sourceType").takeIf { it.isNotEmpty() },
                    sourceIdHash = o.optString("sourceIdHash").takeIf { it.isNotEmpty() },
                    elapsedMs = if (o.has("elapsedMs")) o.getLong("elapsedMs") else null,
                    metadataJson = o.optString("meta").takeIf { it.isNotEmpty() },
                    exceptionClass = o.optString("excClass").takeIf { it.isNotEmpty() },
                    exceptionMessageSafe = o.optString("excMsg").takeIf { it.isNotEmpty() },
                    writeFailureClass = o.optString("wfClass").takeIf { it.isNotEmpty() },
                    writeFailureMessageSafe = o.optString("wfMsg").takeIf { it.isNotEmpty() },
                    isTerminal = o.optBoolean("terminal", false)
                )
            }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }
}
