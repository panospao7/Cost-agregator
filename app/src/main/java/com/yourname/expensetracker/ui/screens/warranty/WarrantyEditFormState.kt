package com.yourname.expensetracker.ui.screens.warranty

import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditError
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditInput
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditValidator
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEndDate
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyReviewState
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * warranty-edit (UF-02) S4: pure, Compose-free logic behind the shared add/edit warranty form
 * ([WarrantyFormDialog]) and the warranty card action enablement (INV-21).
 *
 * No Android or Compose types, no clock (every "now" is a parameter, INV-17), no I/O, and no
 * logging: drafts hold user text and live only in the saved-state bundle. Validation is owned by
 * [WarrantyEditValidator] (INV-4); end-date arithmetic by [WarrantyEndDate] (INV-1, INV-2). This
 * file only wires them to the form.
 */

/** The form fields that can show a validation error (the type dropdown cannot be invalid). */
enum class WarrantyFormField { PRODUCT, MERCHANT, PURCHASE_DATE, DURATION, PHONE }

/** Which primary button label the form shows (derived from the live row, never stored). */
enum class WarrantyPrimaryAction { SAVE, SAVE_AND_CONFIRM }

/** One of the (at most one) outcome sentences shown above the buttons. */
enum class WarrantyOutcomeLine {
    NONE,
    CONFIRM_ACTIVE,
    CONFIRM_EXPIRED,
    REACTIVATE,
    WILL_EXPIRE
}

/** Live coverage preview. Hidden (never zero or a placeholder date) while the inputs are invalid. */
sealed interface WarrantyCoveragePreview {
    object Hidden : WarrantyCoveragePreview

    /**
     * @property endExclusive the exclusive end that would be stored
     * @property lastCoveredDay a millisecond on the last covered day (end minus 1 ms), for display
     * @property isPast true when [endExclusive] is before the reference time (shown as "Expired on")
     */
    data class Shown(
        val endExclusive: Long,
        val lastCoveredDay: Long,
        val isPast: Boolean
    ) : WarrantyCoveragePreview
}

/**
 * The six user-editable fields exactly as typed.
 *
 * @property purchaseDateMillis start (or any instant) of the chosen local purchase day
 * @property durationMonthsText digits only; may be blank or too large to parse
 */
data class WarrantyDraft(
    val productName: String,
    val merchantName: String,
    val purchaseDateMillis: Long,
    val durationMonthsText: String,
    val warrantyType: WarrantyType,
    val supportPhone: String
) {
    /** Parsed months, or null when blank or not representable (treated as out of range). */
    val durationMonths: Int? get() = durationMonthsText.toIntOrNull()

    /**
     * The repository/validator input. An unparseable duration maps to 0, which the validator
     * reports as [WarrantyEditError.DURATION_OUT_OF_RANGE] (never silently clamped).
     */
    fun toInput(): WarrantyEditInput = WarrantyEditInput(
        productName = productName,
        merchantName = merchantName,
        purchaseDate = purchaseDateMillis,
        durationMonths = durationMonths ?: 0,
        warrantyType = warrantyType,
        supportPhone = supportPhone
    ).normalized()
}

/** How the card renders its actions (edit entry placement, UX section 2). */
enum class WarrantyEditPlacement {
    /** Review card (awaiting review or expired-unconfirmed): Confirm, Edit, Reject stack. */
    REVIEW_STACK,

    /** Confirmed ACTIVE card: Edit, Mark Claimed, Delete in a wrapping row. */
    ACTIVE_ROW,

    /** Confirmed EXPIRED card: a start-aligned text button. */
    EXPIRED_TEXT,

    /** CLAIMED / TRANSFERRED cards: no edit entry. */
    NONE
}

/**
 * INV-21: what a card offers and whether it is enabled. All five actions (Edit, Confirm, Reject,
 * Claim, Delete) share the single flag [enabled], which is false while the card's id is mutating.
 */
data class WarrantyCardActionState(
    val placement: WarrantyEditPlacement,
    val showConfirmReject: Boolean,
    val showClaimDelete: Boolean,
    val enabled: Boolean
) {
    val showEdit: Boolean get() = placement != WarrantyEditPlacement.NONE
}

object WarrantyEditFormState {

    /** Default duration of a new manual warranty (unchanged from the previous add dialog). */
    const val DEFAULT_DURATION_MONTHS_TEXT = "24"

    /** Longest accepted duration text; longer input is cut (the validator caps at 120 anyway). */
    const val MAX_DURATION_TEXT_LENGTH = 4

    /** Selectable options of the type dropdown, in display order; map by index, never by label. */
    fun typeOptions(): List<WarrantyType> = WarrantyType.values().toList()

    /** Digits-only filter for the duration field, bounded in length. */
    fun sanitizeDurationText(raw: String): String =
        raw.filter { it.isDigit() }.take(MAX_DURATION_TEXT_LENGTH)

    // ---- drafts -------------------------------------------------------------------------------

    /** Add-mode defaults; the date comes from the ViewModel clock, not the wall clock. */
    fun initialAddDraft(nowMillis: Long): WarrantyDraft = WarrantyDraft(
        productName = "",
        merchantName = "",
        purchaseDateMillis = TimePeriodUtils.getStartOfDay(nowMillis),
        durationMonthsText = DEFAULT_DURATION_MONTHS_TEXT,
        warrantyType = WarrantyType.MANUFACTURER,
        supportPhone = ""
    )

    /** Edit-mode baseline: the six fields of the stored row. */
    fun draftFromWarranty(warranty: Warranty): WarrantyDraft = WarrantyDraft(
        productName = warranty.productName,
        merchantName = warranty.merchantName,
        purchaseDateMillis = warranty.purchaseDate,
        durationMonthsText = warranty.warrantyDurationMonths.toString(),
        warrantyType = warranty.warrantyType,
        supportPhone = warranty.supportPhone.orEmpty()
    )

    /**
     * True when [current] differs from [initial] in the repository's sense (names and phone
     * compared trimmed, blank phone equals empty, date compared by local day, months compared as
     * numbers when both parse). Whitespace-only edits are therefore not dirty.
     */
    fun isDirty(initial: WarrantyDraft, current: WarrantyDraft): Boolean {
        if (initial.productName.trim() != current.productName.trim()) return true
        if (initial.merchantName.trim() != current.merchantName.trim()) return true
        if (TimePeriodUtils.getStartOfDay(initial.purchaseDateMillis) !=
            TimePeriodUtils.getStartOfDay(current.purchaseDateMillis)
        ) {
            return true
        }
        val sameMonths = initial.durationMonths?.let { it == current.durationMonths }
            ?: (initial.durationMonthsText == current.durationMonthsText)
        if (!sameMonths) return true
        if (initial.warrantyType != current.warrantyType) return true
        return initial.supportPhone.trim() != current.supportPhone.trim()
    }

    // ---- saved-state helpers --------------------------------------------------------------------

    /** Local epoch day of the draft's purchase date (saved-state friendly, zone-independent). */
    fun purchaseEpochDay(purchaseDateMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(purchaseDateMillis).atZone(zone).toLocalDate().toEpochDay()

    /** Inverse of [purchaseEpochDay]: start of that local day. */
    fun startOfEpochDay(epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()

    // ---- validation and the field-error map -------------------------------------------------------

    /** First validator failure for [draft], or null when valid (the same call the repository makes). */
    fun validate(draft: WarrantyDraft, nowMillis: Long): WarrantyEditError? =
        WarrantyEditValidator.validate(draft.toInput(), nowMillis)

    /**
     * Per-field errors, so every bad field shows its message at once. Each field is checked by the
     * validator itself with all other fields replaced by known-valid probe values, so the validator
     * stays the single owner of the rules. Empty exactly when [validate] returns null.
     */
    fun fieldErrors(draft: WarrantyDraft, nowMillis: Long): Map<WarrantyFormField, WarrantyEditError> {
        val probe = WarrantyEditInput(
            productName = PROBE_NAME,
            merchantName = PROBE_NAME,
            purchaseDate = nowMillis,
            durationMonths = PROBE_MONTHS,
            warrantyType = draft.warrantyType,
            supportPhone = null
        )
        val input = draft.toInput()
        val result = LinkedHashMap<WarrantyFormField, WarrantyEditError>()
        fun check(field: WarrantyFormField, candidate: WarrantyEditInput) {
            WarrantyEditValidator.validate(candidate, nowMillis)?.let { result[field] = it }
        }
        check(WarrantyFormField.PRODUCT, probe.copy(productName = input.productName))
        check(WarrantyFormField.MERCHANT, probe.copy(merchantName = input.merchantName))
        check(WarrantyFormField.PURCHASE_DATE, probe.copy(purchaseDate = input.purchaseDate))
        check(WarrantyFormField.DURATION, probe.copy(durationMonths = input.durationMonths))
        check(WarrantyFormField.PHONE, probe.copy(supportPhone = input.supportPhone))
        return result
    }

    /**
     * Fields to mark as touched (and show an error on) for a repository `Invalid(reason)` that got
     * through UI validation (race or clock edge). Empty means the reason maps to no known field and
     * the dialog shows the generic invalid banner instead.
     */
    fun fieldsForError(reason: WarrantyEditError, draft: WarrantyDraft): Set<WarrantyFormField> =
        when (reason) {
            WarrantyEditError.PRODUCT_BLANK -> setOf(WarrantyFormField.PRODUCT)
            WarrantyEditError.MERCHANT_BLANK -> setOf(WarrantyFormField.MERCHANT)
            WarrantyEditError.NAME_TOO_LONG -> {
                val input = draft.toInput()
                buildSet {
                    if (input.productName.length > WarrantyEditValidator.MAX_NAME_LENGTH) {
                        add(WarrantyFormField.PRODUCT)
                    }
                    if (input.merchantName.length > WarrantyEditValidator.MAX_NAME_LENGTH) {
                        add(WarrantyFormField.MERCHANT)
                    }
                }
            }
            WarrantyEditError.PHONE_TOO_LONG -> setOf(WarrantyFormField.PHONE)
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE -> setOf(WarrantyFormField.PURCHASE_DATE)
            WarrantyEditError.DURATION_OUT_OF_RANGE -> setOf(WarrantyFormField.DURATION)
        }

    /** Errors to display: only fields the user has touched (or that a failed save marked). */
    fun visibleErrors(
        errors: Map<WarrantyFormField, WarrantyEditError>,
        touched: Set<WarrantyFormField>
    ): Map<WarrantyFormField, WarrantyEditError> = errors.filterKeys { it in touched }

    // ---- save enablement and labels -----------------------------------------------------------------

    /**
     * Save is enabled when the form is valid AND (dirty OR the row is in review, INV-8: an in-review
     * row may "Save and confirm" pristine, every other row needs a change, INV-10) AND not saving.
     */
    fun isSaveEnabled(valid: Boolean, dirty: Boolean, inReview: Boolean, saving: Boolean): Boolean =
        valid && (dirty || inReview) && !saving

    /** "Save and confirm" only while the live row is in review (edit mode); otherwise "Save". */
    fun primaryAction(isEdit: Boolean, reviewState: WarrantyReviewState): WarrantyPrimaryAction =
        if (isEdit && reviewState != WarrantyReviewState.NOT_IN_REVIEW) {
            WarrantyPrimaryAction.SAVE_AND_CONFIRM
        } else {
            WarrantyPrimaryAction.SAVE
        }

    // ---- preview and outcome line ---------------------------------------------------------------------

    /**
     * Live coverage preview. Hidden while the duration or purchase date is invalid. With a stored
     * row whose purchase day and months are unchanged it shows the STORED end (INV-2), otherwise the
     * end [WarrantyEndDate.exclusiveEnd] would produce; both go through [WarrantyEndDate].
     */
    fun coveragePreview(draft: WarrantyDraft, stored: Warranty?, nowMillis: Long): WarrantyCoveragePreview {
        val errors = fieldErrors(draft, nowMillis)
        val months = draft.durationMonths
        if (months == null ||
            WarrantyFormField.DURATION in errors ||
            WarrantyFormField.PURCHASE_DATE in errors
        ) {
            return WarrantyCoveragePreview.Hidden
        }
        val end = if (stored == null) {
            WarrantyEndDate.exclusiveEnd(draft.purchaseDateMillis, months)
        } else {
            WarrantyEndDate.resolveEditDates(
                storedPurchase = stored.purchaseDate,
                storedEnd = stored.warrantyEndDate,
                storedMonths = stored.warrantyDurationMonths,
                chosenPurchase = draft.purchaseDateMillis,
                chosenMonths = months
            ).warrantyEndDate
        }
        return WarrantyCoveragePreview.Shown(
            endExclusive = end,
            lastCoveredDay = end - 1,
            isPast = end < nowMillis
        )
    }

    /**
     * The outcome sentence for edit mode (add mode and a hidden preview give [WarrantyOutcomeLine.NONE]):
     * review rows say they will be confirmed (active or listed as expired by the preview end); a
     * confirmed EXPIRED row with a future end reactivates (O-5); a confirmed ACTIVE row with a past
     * end expires. Review state comes only from [reviewState] (INV-10).
     */
    fun outcomeLine(
        stored: Warranty?,
        reviewState: WarrantyReviewState,
        preview: WarrantyCoveragePreview
    ): WarrantyOutcomeLine {
        if (stored == null || preview !is WarrantyCoveragePreview.Shown) return WarrantyOutcomeLine.NONE
        return when {
            reviewState != WarrantyReviewState.NOT_IN_REVIEW ->
                if (preview.isPast) WarrantyOutcomeLine.CONFIRM_EXPIRED else WarrantyOutcomeLine.CONFIRM_ACTIVE
            stored.status == WarrantyStatus.EXPIRED && !preview.isPast -> WarrantyOutcomeLine.REACTIVATE
            stored.status == WarrantyStatus.ACTIVE && preview.isPast -> WarrantyOutcomeLine.WILL_EXPIRE
            else -> WarrantyOutcomeLine.NONE
        }
    }

    // ---- edit outcome gating --------------------------------------------------------------------------

    /**
     * True only when an edit result belongs to the dialog open right now AND that dialog is the one
     * awaiting a result. A result tagged with any other id, or arriving with no dialog open or no
     * pending save, is dropped.
     */
    fun shouldApplyEditResult(
        resultWarrantyId: Long,
        openWarrantyId: Long?,
        awaitingWarrantyId: Long?
    ): Boolean =
        openWarrantyId != null &&
            resultWarrantyId == openWarrantyId &&
            awaitingWarrantyId == openWarrantyId

    // ---- date picker bounds --------------------------------------------------------------------------

    /**
     * Whether a date-picker day (UTC midnight millis, as the M3 picker reports) is selectable:
     * 1970-01-01 through today + [WarrantyEditValidator.MAX_PURCHASE_DAYS_AHEAD] days (O-11). Mirrors
     * the validator's range, so PURCHASE_DATE_OUT_OF_RANGE is normally unreachable from the picker.
     */
    fun isPickerDaySelectable(
        pickerUtcMillis: Long,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        val day = Instant.ofEpochMilli(pickerUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return !day.isBefore(WarrantyEditValidator.MIN_PURCHASE_DAY) &&
            !day.isAfter(today.plusDays(WarrantyEditValidator.MAX_PURCHASE_DAYS_AHEAD))
    }

    // ---- card actions (INV-21) -----------------------------------------------------------------------

    /**
     * INV-21 / AE-22: edit entry placement per card state and the single enablement flag. Edit,
     * Confirm, Reject, Claim and Delete are all disabled while [isMutating] (the card's id is in
     * `mutatingWarrantyIds`, including the id being saved by the open form). Review state wins over
     * status, as before; CLAIMED / TRANSFERRED cards have no actions.
     */
    fun cardActionState(
        status: WarrantyStatus,
        reviewState: WarrantyReviewState,
        isMutating: Boolean
    ): WarrantyCardActionState {
        val enabled = !isMutating
        return when {
            reviewState != WarrantyReviewState.NOT_IN_REVIEW -> WarrantyCardActionState(
                placement = WarrantyEditPlacement.REVIEW_STACK,
                showConfirmReject = true,
                showClaimDelete = false,
                enabled = enabled
            )
            status == WarrantyStatus.ACTIVE -> WarrantyCardActionState(
                placement = WarrantyEditPlacement.ACTIVE_ROW,
                showConfirmReject = false,
                showClaimDelete = true,
                enabled = enabled
            )
            status == WarrantyStatus.EXPIRED -> WarrantyCardActionState(
                placement = WarrantyEditPlacement.EXPIRED_TEXT,
                showConfirmReject = false,
                showClaimDelete = false,
                enabled = enabled
            )
            else -> WarrantyCardActionState(
                placement = WarrantyEditPlacement.NONE,
                showConfirmReject = false,
                showClaimDelete = false,
                enabled = enabled
            )
        }
    }

    private const val PROBE_NAME = "x"
    private const val PROBE_MONTHS = 1
}
