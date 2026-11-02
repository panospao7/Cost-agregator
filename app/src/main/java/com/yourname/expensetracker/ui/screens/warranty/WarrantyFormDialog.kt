package com.yourname.expensetracker.ui.screens.warranty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditError
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditInput
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditValidator
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyReviewState
import com.yourname.expensetracker.ui.components.feature.FormDateField
import com.yourname.expensetracker.ui.components.feature.FormDropdown
import com.yourname.expensetracker.ui.components.feature.FormTextField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Saved-state saver for the six draft fields (user text lives only in the saved-state bundle, is
 * never logged, and is dropped when the dialog leaves composition). The purchase day is stored as a
 * local epoch day, the type by enum name.
 */
private val WarrantyDraftSaver: Saver<WarrantyDraft, Any> = listSaver(
    save = { draft ->
        listOf<Any>(
            draft.productName,
            draft.merchantName,
            WarrantyEditFormState.purchaseEpochDay(draft.purchaseDateMillis),
            draft.durationMonthsText,
            draft.warrantyType.name,
            draft.supportPhone
        )
    },
    restore = { saved ->
        WarrantyDraft(
            productName = saved[0] as String,
            merchantName = saved[1] as String,
            purchaseDateMillis = WarrantyEditFormState.startOfEpochDay(saved[2] as Long),
            durationMonthsText = saved[3] as String,
            warrantyType = WarrantyType.valueOf(saved[4] as String),
            supportPhone = saved[5] as String
        )
    }
)

/**
 * warranty-edit (UF-02): the one form shared by add ([editTarget] == null) and edit.
 *
 * Stateless towards the ViewModel: the caller passes the live row, its review state, the saving flag
 * (the id is in `mutatingWarrantyIds`), the in-dialog banner text and a repository `Invalid` reason;
 * the form reports a valid [WarrantyEditInput] through [onSave] and a confirmed close through
 * [onDismiss]. Closing rule: a clean form closes, a dirty form asks to discard, and while saving
 * every close path is ignored.
 *
 * @param editTarget the live stored row to edit, or null for add mode
 * @param reviewState the live review state of [editTarget] (ignored in add mode)
 * @param referenceNowMillis the ViewModel clock (G-TIME-01: never the wall clock)
 * @param isSaving true while the target id is being mutated
 * @param bannerMessage a bounded save-failure message (string resource text), or null
 * @param invalidReason a repository `Invalid` reason to map onto fields, or null
 * @param onInvalidReasonConsumed called once [invalidReason] has been applied
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarrantyFormDialog(
    editTarget: Warranty?,
    reviewState: WarrantyReviewState,
    referenceNowMillis: Long,
    isSaving: Boolean,
    bannerMessage: String?,
    invalidReason: WarrantyEditError?,
    onInvalidReasonConsumed: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (WarrantyEditInput) -> Unit
) {
    val isEdit = editTarget != null
    val targetId = editTarget?.id

    var draft by rememberSaveable(targetId, stateSaver = WarrantyDraftSaver) {
        mutableStateOf(
            if (editTarget != null) {
                WarrantyEditFormState.draftFromWarranty(editTarget)
            } else {
                WarrantyEditFormState.initialAddDraft(referenceNowMillis)
            }
        )
    }
    // Bit mask over WarrantyFormField.ordinal: which fields may show their error.
    var touchedMask by rememberSaveable(targetId) { mutableStateOf(0) }
    var showDiscardConfirm by rememberSaveable(targetId) { mutableStateOf(false) }
    var showGenericInvalid by remember(targetId) { mutableStateOf(false) }

    // Dirty baseline: the fresh stored row in edit mode (the draft wins over later row changes),
    // fixed defaults in add mode.
    val addBaseline = remember(targetId) { WarrantyEditFormState.initialAddDraft(referenceNowMillis) }
    val baseline = if (editTarget != null) WarrantyEditFormState.draftFromWarranty(editTarget) else addBaseline

    val touched = WarrantyFormField.values().filter { touchedMask and (1 shl it.ordinal) != 0 }.toSet()
    fun touch(vararg fields: WarrantyFormField) {
        fields.forEach { touchedMask = touchedMask or (1 shl it.ordinal) }
    }
    fun edit(field: WarrantyFormField?, transform: (WarrantyDraft) -> WarrantyDraft) {
        draft = transform(draft)
        if (field != null) touch(field)
        showGenericInvalid = false
    }

    LaunchedEffect(invalidReason) {
        if (invalidReason != null) {
            val fields = WarrantyEditFormState.fieldsForError(invalidReason, draft)
            if (fields.isEmpty()) showGenericInvalid = true else fields.forEach { touch(it) }
            onInvalidReasonConsumed()
        }
    }

    val allErrors = WarrantyEditFormState.fieldErrors(draft, referenceNowMillis)
    val visibleErrors = WarrantyEditFormState.visibleErrors(allErrors, touched)
    val dirty = WarrantyEditFormState.isDirty(baseline, draft)
    val inReview = isEdit && reviewState != WarrantyReviewState.NOT_IN_REVIEW
    val saveEnabled = WarrantyEditFormState.isSaveEnabled(
        valid = allErrors.isEmpty(),
        dirty = dirty,
        inReview = inReview,
        saving = isSaving
    )
    val primaryAction = WarrantyEditFormState.primaryAction(isEdit, reviewState)
    val preview = WarrantyEditFormState.coveragePreview(draft, editTarget, referenceNowMillis)
    val outcomeLine = WarrantyEditFormState.outcomeLine(editTarget, reviewState, preview)

    fun requestClose() {
        when {
            isSaving -> Unit
            dirty -> showDiscardConfirm = true
            else -> onDismiss()
        }
    }

    val dateFormat = remember { DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault()) }
    fun formatDay(millis: Long): String =
        dateFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    val selectableDates = remember(referenceNowMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                WarrantyEditFormState.isPickerDaySelectable(utcTimeMillis, referenceNowMillis)
        }
    }

    val types = remember { WarrantyEditFormState.typeOptions() }
    val typeLabels = types.map { stringResource(warrantyTypeLabelRes(it)) }
    val bannerText = bannerMessage
        ?: if (showGenericInvalid) stringResource(R.string.warranty_edit_error_invalid) else null

    AlertDialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = !isSaving
        ),
        title = {
            Text(
                stringResource(
                    if (isEdit) R.string.warranty_edit_title else R.string.warranty_manual_add_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (editTarget != null) {
                    ProvenanceLines(editTarget)
                }
                if (bannerText != null) {
                    ErrorBanner(bannerText)
                }
                FormTextField(
                    value = draft.productName,
                    onValueChange = { v -> edit(WarrantyFormField.PRODUCT) { it.copy(productName = v) } },
                    label = stringResource(R.string.warranty_manual_product_name),
                    isError = WarrantyFormField.PRODUCT in visibleErrors,
                    supportingText = fieldErrorText(visibleErrors[WarrantyFormField.PRODUCT]),
                    enabled = !isSaving
                )
                FormTextField(
                    value = draft.merchantName,
                    onValueChange = { v -> edit(WarrantyFormField.MERCHANT) { it.copy(merchantName = v) } },
                    label = stringResource(R.string.warranty_manual_merchant_name),
                    isError = WarrantyFormField.MERCHANT in visibleErrors,
                    supportingText = fieldErrorText(visibleErrors[WarrantyFormField.MERCHANT]),
                    enabled = !isSaving
                )
                FormDateField(
                    dateMillis = draft.purchaseDateMillis,
                    onDateSelected = { picked ->
                        edit(WarrantyFormField.PURCHASE_DATE) { it.copy(purchaseDateMillis = picked) }
                    },
                    label = stringResource(R.string.warranty_manual_purchase_date),
                    enabled = !isSaving,
                    isError = WarrantyFormField.PURCHASE_DATE in visibleErrors,
                    supportingText = fieldErrorText(visibleErrors[WarrantyFormField.PURCHASE_DATE]),
                    selectableDates = selectableDates,
                    wholeFieldClickable = true,
                    fieldContentDescription = stringResource(
                        R.string.add_expense_date_selector_cd_format,
                        formatDay(draft.purchaseDateMillis)
                    )
                )
                OutlinedTextField(
                    value = draft.durationMonthsText,
                    onValueChange = { raw ->
                        edit(WarrantyFormField.DURATION) {
                            it.copy(durationMonthsText = WarrantyEditFormState.sanitizeDurationText(raw))
                        }
                    },
                    label = { Text(stringResource(R.string.warranty_manual_duration_months)) },
                    isError = WarrantyFormField.DURATION in visibleErrors,
                    supportingText = fieldErrorText(visibleErrors[WarrantyFormField.DURATION])
                        ?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                FormDropdown(
                    value = typeLabels[types.indexOf(draft.warrantyType).coerceAtLeast(0)],
                    onValueSelected = {},
                    label = stringResource(R.string.warranty_field_type),
                    options = typeLabels,
                    enabled = !isSaving,
                    selectedIndex = types.indexOf(draft.warrantyType),
                    // Mapped by index into the enum order, never by label text.
                    onIndexSelected = { index -> edit(null) { it.copy(warrantyType = types[index]) } }
                )
                FormTextField(
                    value = draft.supportPhone,
                    onValueChange = { v -> edit(WarrantyFormField.PHONE) { it.copy(supportPhone = v) } },
                    label = stringResource(R.string.warranty_manual_support_phone_optional),
                    isError = WarrantyFormField.PHONE in visibleErrors,
                    supportingText = fieldErrorText(visibleErrors[WarrantyFormField.PHONE]),
                    enabled = !isSaving
                )
                (preview as? WarrantyCoveragePreview.Shown)?.let { shown ->
                    Text(
                        text = stringResource(
                            if (shown.isPast) R.string.warranty_expired_date else R.string.warranty_expires_date,
                            formatDay(shown.lastCoveredDay)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                outcomeLineRes(outcomeLine)?.let { res ->
                    Row(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            val savingDescription = stringResource(R.string.warranty_edit_saving_cd)
            Button(
                onClick = { onSave(draft.toInput()) },
                enabled = saveEnabled,
                modifier = Modifier.semantics {
                    if (isSaving) stateDescription = savingDescription
                }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(
                            when (primaryAction) {
                                WarrantyPrimaryAction.SAVE_AND_CONFIRM -> R.string.warranty_edit_save_and_confirm
                                WarrantyPrimaryAction.SAVE -> R.string.action_save
                            }
                        )
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { requestClose() }, enabled = !isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.confirm_discard_title)) },
            text = { Text(stringResource(R.string.confirm_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirm = false
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.warranty_edit_discard_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.warranty_edit_keep_editing))
                }
            }
        )
    }
}

/** Read-only provenance (never a field): auto-detected badge and confidence, or "Added manually". */
@Composable
private fun ProvenanceLines(warranty: Warranty) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (warranty.autoDetected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.warranty_badge_auto_detected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.warranty_confidence_format, warranty.extractionConfidence.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                text = stringResource(R.string.warranty_edit_provenance_manual),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (warranty.receiptId != null) {
            Text(
                text = stringResource(R.string.warranty_edit_provenance_receipt_linked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** In-dialog failure banner (UX-2): text plus icon, announced as a live region. */
@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Static, bounded field messages (never user text); null when the field has no visible error. */
@Composable
private fun fieldErrorText(error: WarrantyEditError?): String? = when (error) {
    null -> null
    WarrantyEditError.PRODUCT_BLANK -> stringResource(R.string.warranty_edit_error_product_blank)
    WarrantyEditError.MERCHANT_BLANK -> stringResource(R.string.warranty_edit_error_merchant_blank)
    WarrantyEditError.NAME_TOO_LONG ->
        stringResource(R.string.warranty_edit_error_too_long, WarrantyEditValidator.MAX_NAME_LENGTH)
    WarrantyEditError.PHONE_TOO_LONG ->
        stringResource(R.string.warranty_edit_error_too_long, WarrantyEditValidator.MAX_PHONE_LENGTH)
    WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE -> stringResource(R.string.warranty_edit_error_date_range)
    WarrantyEditError.DURATION_OUT_OF_RANGE -> stringResource(
        R.string.warranty_edit_error_duration_range,
        WarrantyEditValidator.MIN_DURATION_MONTHS,
        WarrantyEditValidator.MAX_DURATION_MONTHS
    )
}

private fun warrantyTypeLabelRes(type: WarrantyType): Int = when (type) {
    WarrantyType.MANUFACTURER -> R.string.warranty_type_manufacturer
    WarrantyType.EXTENDED -> R.string.warranty_type_extended
    WarrantyType.STORE -> R.string.warranty_type_store
    WarrantyType.THIRD_PARTY -> R.string.warranty_type_third_party
}

private fun outcomeLineRes(line: WarrantyOutcomeLine): Int? = when (line) {
    WarrantyOutcomeLine.NONE -> null
    WarrantyOutcomeLine.CONFIRM_ACTIVE -> R.string.warranty_edit_outcome_confirm_active
    WarrantyOutcomeLine.CONFIRM_EXPIRED -> R.string.warranty_edit_outcome_confirm_expired
    WarrantyOutcomeLine.REACTIVATE -> R.string.warranty_edit_outcome_reactivate
    WarrantyOutcomeLine.WILL_EXPIRE -> R.string.warranty_edit_outcome_will_expire
}
