package com.yourname.expensetracker.ui.screens.addexpense

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.ui.components.asString
import com.yourname.expensetracker.ui.components.currency.CurrencyOpenSettingRequest
import com.yourname.expensetracker.ui.components.currency.CurrencyRateRegionContent
import com.yourname.expensetracker.ui.components.currency.CurrencyRegionUi
import com.yourname.expensetracker.ui.components.currency.CurrencySelectionControl
import com.yourname.expensetracker.ui.components.currency.currencySymbolFor
import com.yourname.expensetracker.ui.navigation.InstallmentsFeature
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.ui.util.UiTimeUtils
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.*

private fun findHostActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

@Composable
private fun getTransactionTypeLabel(type: TransactionType): String {
    return when (type) {
        TransactionType.PURCHASE -> stringResource(R.string.transaction_type_purchase)
        TransactionType.DEPOSIT -> stringResource(R.string.transaction_type_deposit)
        TransactionType.WITHDRAWAL -> stringResource(R.string.transaction_type_withdrawal)
        TransactionType.TRANSFER -> stringResource(R.string.transaction_type_transfer)
        TransactionType.UNKNOWN -> stringResource(R.string.transaction_type_unknown)
    }
}

@Composable
private fun getRecurrenceFrequencyLabel(frequency: com.yourname.expensetracker.domain.model.RecurrenceFrequency): String {
    return when (frequency) {
        com.yourname.expensetracker.domain.model.RecurrenceFrequency.WEEKLY -> stringResource(R.string.recurrence_weekly)
        com.yourname.expensetracker.domain.model.RecurrenceFrequency.BIWEEKLY -> stringResource(R.string.recurrence_biweekly)
        com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY -> stringResource(R.string.recurrence_monthly)
        com.yourname.expensetracker.domain.model.RecurrenceFrequency.QUARTERLY -> stringResource(R.string.recurrence_quarterly)
        com.yourname.expensetracker.domain.model.RecurrenceFrequency.SEMI_ANNUALLY -> stringResource(R.string.recurrence_semi_annually)
        com.yourname.expensetracker.domain.model.RecurrenceFrequency.ANNUALLY -> stringResource(R.string.recurrence_annually)
        com.yourname.expensetracker.domain.model.RecurrenceFrequency.IRREGULAR -> stringResource(R.string.recurrence_irregular)
    }
}

/**
 * The amount field. Its leading symbol follows the SELECTED currency (MEC F4); it is not tappable (the
 * currency control next to it is). Per-currency amount errors arrive as [amountError] (supporting text).
 */
@Composable
private fun AmountInputField(
    amount: String,
    onAmountChange: (String) -> Unit,
    amountError: UiText?,
    amountNote: QuickAddFieldNote?,
    currencyCode: String?,
    amountCd: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = amount,
        onValueChange = onAmountChange,
        label = { Text(stringResource(com.yourname.expensetracker.R.string.amount_label)) },
        placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.amount_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { onDone() }
        ),
        // A quick-add amount flag blocks Save (empty amount), so it uses the error style.
        isError = amountError != null || amountNote?.kind == QuickAddFieldNoteKind.FLAG,
        supportingText = when {
            amountError != null -> {
                { Text(amountError.asString()) }
            }
            amountNote != null -> {
                { QuickAddNoteText(amountNote) }
            }
            else -> null
        },
        leadingIcon = {
            val currencySymbol = remember(currencyCode) { currencySymbolFor(currencyCode) }
            Text(
                text = currencySymbol,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        modifier = modifier.semantics { contentDescription = amountCd }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    initialAmount: String? = null,
    initialMerchant: String? = null,
    onOpenExchangeRateSetting: () -> Unit = {},
    viewModel: AddExpenseViewModel = hiltViewModel(),
    // Loan-installments S11b: the section renders nothing unless the feature flag is on.
    installmentsFeatureEnabled: Boolean = InstallmentsFeature.ENABLED
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // nl-quick-add (C4): the summary describes the last Fill and survives later edits and rotation (G2-13,
    // item 71a): the snapshot lives in the ViewModel state; only the per-field markers follow the live
    // origins. No edit mode exists in this sheet (create only), so the quick-add section is always shown (U2).
    val fieldNotes = QuickAddField.values().associateWith { field ->
        QuickAddUiMapper.fieldNote(
            origin = state.fieldOrigins[field] ?: FieldOrigin.DEFAULT,
            flag = state.fieldFlags[field],
            // MEC F4: the typed amount is in the SELECTED currency, so the notes name that one.
            homeCurrency = state.selectedCurrency
        )
    }
    val selectedCategory = categories.firstOrNull { it.id == state.selectedCategoryId }
    val categoryChip = QuickAddUiMapper.categoryChip(state.categoryFillSource, selectedCategory?.name)

    // MEC AE-34: capture the entry zone once per sheet session (kept in the ViewModel only). The capture is
    // null-guarded, so a configuration change keeps the zone; leaving the sheet for any reason other than a
    // configuration change (e.g. host navigation without reset()) clears it so the next session re-captures.
    val hostActivity = findHostActivity(LocalContext.current)
    DisposableEffect(Unit) {
        viewModel.onSheetOpened()
        onDispose {
            if (hostActivity?.isChangingConfigurations != true) {
                viewModel.onSheetLeft()
            }
        }
    }

    // MEC D-11: "Open exchange-rate setting". The view model decides whether to confirm first; the sheet
    // only shows the dialog and, once the request is NAVIGATE, hands over to the host and consumes it.
    LaunchedEffect(state.openSettingRequest) {
        if (state.openSettingRequest == CurrencyOpenSettingRequest.NAVIGATE) {
            onOpenExchangeRateSetting()
            viewModel.consumeOpenSettingRequest()
        }
    }
    if (state.openSettingRequest == CurrencyOpenSettingRequest.CONFIRM_NEEDED) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeOpenSettingRequest() },
            title = { Text(stringResource(R.string.mec_open_setting_confirm_title)) },
            text = { Text(stringResource(R.string.mec_open_setting_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    // MEC C-1: the view model discards the entry and raises NAVIGATE; the effect above
                    // makes the single host callback.
                    viewModel.confirmOpenExchangeRateSetting()
                }) {
                    Text(stringResource(R.string.mec_action_open_setting))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.consumeOpenSettingRequest() }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        )
    }

    // "Change currency" on the blocked card asks the currency control to open its list.
    var changeCurrencyRequests by remember { mutableIntStateOf(0) }

    // Handle save result
    LaunchedEffect(state.mutation.isSuccess) {
        if (state.mutation.isSuccess) {
            viewModel.reset()
            onDismiss()
        }
    }

    val incomingPrefill = remember(initialAmount, initialMerchant) {
        initialAmount to initialMerchant
    }

    // Apply incoming prefill only while fields are still pristine
    LaunchedEffect(incomingPrefill) {
        val hasPrefill = incomingPrefill.first != null || incomingPrefill.second != null
        if (hasPrefill) {
            viewModel.setInitialValuesIfBlank(incomingPrefill.first, incomingPrefill.second)
        }
    }

    // ux-flows §9: the Error/Duplicate Card sits at the bottom of the scroll column; bring it into view.
    LaunchedEffect(state.saveResult) {
        val result = state.saveResult
        if (result is SaveResult.Error || result is SaveResult.Duplicate) {
            // Wait one frame so the newly composed Card is measured and maxValue is current.
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    BackHandler {
        viewModel.reset()
        onDismiss()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = SemanticColors.BaseNavy
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            TopAppBar(
                title = { 
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.add_expense_title), 
                        fontWeight = FontWeight.Bold,
                        color = SemanticColors.TextPrimary
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(com.yourname.expensetracker.R.string.close_content_description))
                    }
                },
                actions = {
                    // MEC ux section 4: Save is disabled with a spoken reason for a missing rate or currency.
                    val saveBlockedReason = when (state.currencyRegion) {
                        is CurrencyRegionUi.Blocked -> if (state.isCurrencySaveBlocked) stringResource(R.string.mec_save_disabled_rate) else null
                        is CurrencyRegionUi.Unset -> stringResource(R.string.mec_save_disabled_currency)
                        is CurrencyRegionUi.AmountInvalid -> stringResource(R.string.mec_save_disabled_amount)
                        else -> null
                    }
                    Button(
                        onClick = { viewModel.save() },
                        // S5-027: Also disable while currency is loading
                        enabled = !state.mutation.isRunning && state.homeCurrency != null && !state.isCurrencySaveBlocked,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .semantics {
                                if (saveBlockedReason != null) stateDescription = saveBlockedReason
                            }
                    ) {
                        if (state.mutation.isRunning || state.homeCurrency == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(com.yourname.expensetracker.R.string.save_button))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SemanticColors.BaseNavy,
                    titleContentColor = SemanticColors.TextPrimary
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // === Quick add (typed line, fill-only) ===
                QuickAddSection(
                    summary = QuickAddUiMapper.summaryLines(state.fillSummary, state.selectedCurrency),
                    homeCurrencyLoaded = state.homeCurrency != null,
                    isSaving = state.isSaving,
                    onFill = { viewModel.fill(it) }
                )

                // === Merchant Field with Autocomplete ===
                MerchantFieldWithSuggestions(
                    merchant = state.merchant,
                    onMerchantChange = { viewModel.updateMerchant(it) },
                    suggestions = state.suggestions,
                    showSuggestions = state.showSuggestions,
                    onSuggestionSelected = { viewModel.selectSuggestion(it) },
                    onDismissSuggestions = { viewModel.dismissSuggestions() },
                    error = state.merchantError,
                    categories = categories,
                    onNextFocus = { focusManager.moveFocus(FocusDirection.Down) },
                    fillNote = fieldNotes[QuickAddField.MERCHANT]
                )

                // === Amount Field ===
                // MEC F4: the amount is in the SELECTED currency (null only while home is loading).
                val amountCurrency = state.selectedCurrency
                val amountCd = stringResource(R.string.add_expense_amount_cd, state.amount, amountCurrency ?: "")
                val amountError = state.amountError
                val amountNote = fieldNotes[QuickAddField.AMOUNT]
                // ux-flows section 2: the control and the amount stack at fontScale >= 1.3 or width < 360 dp.
                val stackCurrencyRow = LocalDensity.current.fontScale >= 1.3f ||
                    LocalConfiguration.current.screenWidthDp < 360
                // Loan-installments S11b: an Unset selection (null currency) must stay pickable, else the user could
                // never leave Unset (for example JPY switched to installment mode).
                val currencyControlEnabled = (amountCurrency != null || state.currencyRegion is CurrencyRegionUi.Unset) &&
                    state.currencyRegion !is CurrencyRegionUi.Loading
                if (stackCurrencyRow) {
                    CurrencySelectionControl(
                        selectedCode = amountCurrency,
                        options = state.currencyOptions,
                        homeCode = state.homeCurrency,
                        enabled = currencyControlEnabled,
                        isUnset = state.currencyRegion is CurrencyRegionUi.Unset,
                        readOnly = state.currencyRegion is CurrencyRegionUi.ReadOnly,
                        onSelect = { viewModel.selectCurrency(it) },
                        openRequest = changeCurrencyRequests,
                        onOpenRequestConsumed = { changeCurrencyRequests = 0 },
                        modifier = Modifier.fillMaxWidth()
                    )
                    AmountInputField(
                        amount = state.amount,
                        onAmountChange = { viewModel.updateAmount(it) },
                        amountError = amountError,
                        amountNote = amountNote,
                        currencyCode = amountCurrency,
                        amountCd = amountCd,
                        onDone = { focusManager.clearFocus() },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        CurrencySelectionControl(
                            selectedCode = amountCurrency,
                            options = state.currencyOptions,
                            homeCode = state.homeCurrency,
                            enabled = currencyControlEnabled,
                            isUnset = state.currencyRegion is CurrencyRegionUi.Unset,
                            readOnly = state.currencyRegion is CurrencyRegionUi.ReadOnly,
                            onSelect = { viewModel.selectCurrency(it) },
                            openRequest = changeCurrencyRequests,
                            onOpenRequestConsumed = { changeCurrencyRequests = 0 },
                            modifier = Modifier
                                .width(104.dp)
                                .padding(top = 8.dp)
                        )
                        AmountInputField(
                            amount = state.amount,
                            onAmountChange = { viewModel.updateAmount(it) },
                            amountError = amountError,
                            amountNote = amountNote,
                            currencyCode = amountCurrency,
                            amountCd = amountCd,
                            onDone = { focusManager.clearFocus() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // MEC ux sections 3 and 4: preview line or blocked card directly under the amount. The B1
                // save-error card and a home-unresolved card are never both shown.
                CurrencyRateRegionContent(
                    region = state.currencyRegion,
                    ratesSetting = state.ratesSetting,
                    rateRefresh = state.rateRefresh,
                    ratesToday = state.ratesToday,
                    suppressHomeUnresolvedCard = state.saveResult is SaveResult.Error,
                    onRefresh = { viewModel.refreshRates() },
                    rateFixAction = state.rateFixAction,
                    historyStatus = state.historyStatus,
                    onDownloadRateHistory = { viewModel.onDownloadRateHistory() },
                    onCancelRateHistory = { viewModel.onCancelRateHistory() },
                    onOpenSetting = { viewModel.requestOpenExchangeRateSetting() },
                    onChangeCurrency = { changeCurrencyRequests = 1 },
                    announceKey = state.previewAnnounceKey
                )

                // === Payment Method ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.payment_method_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val cardLabel = stringResource(com.yourname.expensetracker.R.string.payment_method_card)
                    val cashLabel = stringResource(com.yourname.expensetracker.R.string.payment_method_cash)
                    val transferLabel = stringResource(com.yourname.expensetracker.R.string.payment_method_transfer)
                    val selectedStr = stringResource(R.string.add_expense_selected)
                    val notSelectedStr = stringResource(R.string.add_expense_not_selected)
                    val cardCd = stringResource(R.string.add_expense_payment_method_cd_format, cardLabel, if (state.paymentMethod == PaymentMethod.CARD) selectedStr else notSelectedStr)
                    val cashCd = stringResource(R.string.add_expense_payment_method_cd_format, cashLabel, if (state.paymentMethod == PaymentMethod.CASH) selectedStr else notSelectedStr)
                    val transferCd = stringResource(R.string.add_expense_payment_method_cd_format, transferLabel, if (state.paymentMethod == PaymentMethod.BANK_TRANSFER) selectedStr else notSelectedStr)
                    
                    PaymentMethodChip(
                        label = cardLabel,
                        selected = state.paymentMethod == PaymentMethod.CARD,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
                        modifier = Modifier.weight(1f),
                        contentDescription = cardCd
                    )
                    PaymentMethodChip(
                        label = cashLabel,
                        selected = state.paymentMethod == PaymentMethod.CASH,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
                        modifier = Modifier.weight(1f),
                        contentDescription = cashCd
                    )
                    PaymentMethodChip(
                        label = transferLabel,
                        selected = state.paymentMethod == PaymentMethod.BANK_TRANSFER,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.BANK_TRANSFER) },
                        modifier = Modifier.weight(1f),
                        contentDescription = transferCd
                    )
                }

                fieldNotes[QuickAddField.PAYMENT]?.let { QuickAddNoteText(it) }

                // === Category Selector ===
                Text(
                    stringResource(com.yourname.expensetracker.R.string.category_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                // The Fill chip is the category's "Filled" marker; a flag, or a Fill-set category the chip
                // cannot show, falls back to the note. G2-14: the auto-assign caption shows in every add flow.
                val categoryNote = fieldNotes[QuickAddField.CATEGORY]
                if (categoryNote != null &&
                    (categoryNote.kind == QuickAddFieldNoteKind.FLAG || categoryChip == null)
                ) {
                    QuickAddNoteText(categoryNote)
                }
                if (state.selectedCategoryId == null) {
                    Text(
                        stringResource(R.string.add_expense_category_auto_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (categoryChip != null && selectedCategory != null) {
                    QuickAddCategoryChipRow(
                        chip = categoryChip,
                        category = selectedCategory,
                        onRemove = { viewModel.clearCategory() }
                    )
                }
                CategoryGrid(
                    categories = categories,
                    selectedId = state.selectedCategoryId,
                    onSelect = { viewModel.selectCategory(it) }
                )

                // === Date Picker ===
                DateSelector(
                    dateMs = state.date,
                    onDateSelected = { viewModel.updateDate(it) }
                )
                fieldNotes[QuickAddField.DATE]?.let { QuickAddNoteText(it) }

                // === Transaction Type (collapsible) ===
                val transactionTypeLabel = getTransactionTypeLabel(state.transactionType)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleTransactionType() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.transaction_type_prefix, transactionTypeLabel),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showTransactionType) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (state.showTransactionType) {
                            "Transaction type expanded"
                        } else {
                            "Transaction type collapsed"
                        }
                    )
                }

                AnimatedVisibility(visible = state.showTransactionType) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransactionType.values().filter { it != TransactionType.UNKNOWN }.forEach { type ->
                            val typeLabel = getTransactionTypeLabel(type)
                            FilterChip(
                                selected = state.transactionType == type,
                                onClick = { viewModel.selectTransactionType(type) },
                                label = {
                                    Text(
                                        typeLabel,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            )
                        }
                    }
                }

                // === Notes (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleNotes() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(com.yourname.expensetracker.R.string.notes_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showNotes) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (state.showNotes) {
                            "Notes expanded"
                        } else {
                            "Notes collapsed"
                        }
                    )
                }

                AnimatedVisibility(visible = state.showNotes) {
                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = { viewModel.updateNotes(it) },
                        label = { Text(stringResource(com.yourname.expensetracker.R.string.notes_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }

                // MEC 1b INV-1b-20(c): one-shot note after a home change discarded the draft.
                if (state.homeChangeClearedNote) {
                    Text(
                        stringResource(R.string.mec_home_changed_cleared),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }

                // === Recurring Options ===
                // MEC INV-16: Repeat is hidden (not disabled) for a foreign currency; a note takes its place.
                if (state.installments.on) {
                    // Loan-installments S11b: Repeat is hidden while installments are ON.
                    Text(
                        stringResource(R.string.add_expense_installments_repeat_exclusive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                } else if (state.isForeignSelected) {
                    if (state.repeatClearedByCurrency) {
                        Text(
                            stringResource(R.string.mec_repeat_cleared),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    } else {
                        Text(
                            stringResource(R.string.mec_repeat_home_only, state.homeCurrency.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(com.yourname.expensetracker.R.string.repeat_transaction_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Switch(
                            checked = state.isRecurring,
                            onCheckedChange = { viewModel.toggleRecurring() }
                        )
                    }
                }

                AnimatedVisibility(visible = state.isRecurring && !state.isForeignSelected) {
                    Column {
                        Text(
                            stringResource(com.yourname.expensetracker.R.string.frequency_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Simple horizontal scroll for frequencies
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.yourname.expensetracker.domain.model.RecurrenceFrequency.values()
                                .filter { it != com.yourname.expensetracker.domain.model.RecurrenceFrequency.IRREGULAR }
                                .forEach { freq ->
                                    val freqLabel = getRecurrenceFrequencyLabel(freq)
                                    FilterChip(
                                        selected = state.recurrenceFrequency == freq,
                                        onClick = { viewModel.setRecurrenceFrequency(freq) },
                                        label = { 
                                            Text(freqLabel) 
                                        }
                                    )
                                }
                        }
                    }
                }

                // === Pay in installments (loan-installments S11b; dormant while the feature flag is off) ===
                AddExpenseInstallmentsSection(
                    state = state,
                    viewModel = viewModel,
                    featureEnabled = installmentsFeatureEnabled
                )

                // === Not Mine Expense ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.add_expense_not_mine),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.isNotMine,
                        onCheckedChange = { viewModel.setIsNotMine(it) }
                    )
                }

                AnimatedVisibility(visible = state.isNotMine) {
                    OutlinedTextField(
                        value = state.ownerName,
                        onValueChange = { viewModel.updateOwnerName(it) },
                        label = { Text(stringResource(R.string.add_expense_owner_name_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // === Shared Expense ===
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.add_expense_shared),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = state.isSharedExpense,
                        onCheckedChange = { viewModel.setIsSharedExpense(it) }
                    )
                }

                AnimatedVisibility(visible = state.isSharedExpense) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.sharedWithName,
                            onValueChange = { viewModel.updateSharedWithName(it) },
                            label = { Text(stringResource(R.string.add_expense_shared_with_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = state.mySharePercentage,
                                onValueChange = { viewModel.updateMySharePercentage(it) },
                                label = { Text(stringResource(R.string.add_expense_my_share_label)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.add_expense_share_placeholder)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next
                                )
                            )
                            OutlinedTextField(
                                value = state.myShareAmount,
                                onValueChange = { viewModel.updateMyShareAmount(it) },
                                label = { Text(stringResource(R.string.add_expense_or_amount_label)) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.add_expense_amount_placeholder_short)) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // === Transfer Direction (show when TRANSFER selected) ===
                AnimatedVisibility(visible = state.transactionType == TransactionType.TRANSFER) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.add_expense_transfer_direction),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = state.transferDirection == TransferDirection.INCOMING,
                                onClick = { viewModel.setTransferDirection(TransferDirection.INCOMING) },
                                label = { Text(stringResource(R.string.add_expense_incoming)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = state.transferDirection == TransferDirection.OUTGOING,
                                onClick = { viewModel.setTransferDirection(TransferDirection.OUTGOING) },
                                label = { Text(stringResource(R.string.add_expense_outgoing)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = state.transferAccountName,
                            onValueChange = { viewModel.updateTransferAccountName(it) },
                            label = { Text(stringResource(R.string.add_expense_account_name_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.add_expense_account_placeholder)) }
                        )
                    }
                }

                // === Error Messages ===
                when (val result = state.saveResult) {
                    is SaveResult.Duplicate -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
                        ) {
                            Text(
                                stringResource(com.yourname.expensetracker.R.string.error_duplicate_transaction),
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    is SaveResult.Error -> {
                        // ux-flows §9: keep the "❌ %1$s" wrapper, but TalkBack reads the text-only
                        // content description so the leading glyph is not announced.
                        val errorText = result.message.asString()
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
                        ) {
                            Text(
                                stringResource(R.string.add_expense_error_format, errorText),
                                modifier = Modifier
                                    .padding(12.dp)
                                    .semantics { contentDescription = errorText },
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    else -> {}
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun MerchantFieldWithSuggestions(
    merchant: String,
    onMerchantChange: (String) -> Unit,
    suggestions: List<MerchantSuggestion>,
    showSuggestions: Boolean,
    onSuggestionSelected: (MerchantSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    error: UiText?,
    categories: List<Category>,
    onNextFocus: () -> Unit,
    fillNote: QuickAddFieldNote? = null
) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }

    Column {
        OutlinedTextField(
            value = merchant,
            onValueChange = onMerchantChange,
            label = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_label)) },
            placeholder = { Text(stringResource(com.yourname.expensetracker.R.string.merchant_placeholder)) },
            singleLine = true,
            isError = error != null,
            supportingText = when {
                error != null -> {
                    { Text(error.asString()) }
                }
                fillNote != null -> {
                    { QuickAddNoteText(fillNote) }
                }
                else -> null
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNextFocus() }),
            modifier = Modifier.fillMaxWidth()
        )

        // Suggestions dropdown
        AnimatedVisibility(visible = showSuggestions && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        val category = suggestion.categoryId?.let { categoryMap[it] }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionSelected(suggestion) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Category icon
                                if (category != null) {
                                    val catColor = remember(category.color) {
                                        try {
                                            Color(android.graphics.Color.parseColor(category.color))
                                        } catch (e: Exception) {
                                            Color.Gray
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(catColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(category.icon, fontSize = 16.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        suggestion.merchant,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            if (category != null) append(category.name)
                                            if (suggestion.txCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append(stringResource(com.yourname.expensetracker.R.string.visits_suffix_format, suggestion.txCount))
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    stringResource(com.yourname.expensetracker.R.string.avg_amount_format, suggestion.avgAmount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (suggestion != suggestions.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PaymentMethodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = ""
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CategoryGrid(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    // Wrapping flow layout using multiple rows
    val chunked = remember(categories) { categories.chunked(4) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunked.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
            row.forEach { category ->
                val isSelected = selectedId == category.id
                val catColor = remember(category.color) {
                    try {
                        Color(android.graphics.Color.parseColor(category.color))
                    } catch (e: Exception) {
                        Color.Gray
                    }
                }
                val selectedStr = stringResource(R.string.add_expense_selected)
                val notSelectedStr = stringResource(R.string.add_expense_not_selected)
                val selectedState = if (isSelected) selectedStr else notSelectedStr
                val categoryCd = stringResource(R.string.add_expense_category_cd_format, category.name, selectedState)
                val categoryIconCd = stringResource(R.string.add_expense_category_icon_cd, category.name)
                Surface(
                    onClick = { onSelect(category.id) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = categoryCd
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) catColor.copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(
                        2.dp, catColor
                    ) else null
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            category.icon,
                            fontSize = 20.sp,
                            modifier = Modifier.semantics { contentDescription = categoryIconCd }
                        )
                        Text(
                            category.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
                // Fill remaining space in last row
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy, HH:mm", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }

    val formattedDate = dateFormat.format(Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault()))
    val dateSelectorCd = stringResource(R.string.add_expense_date_selector_cd_format, formattedDate)

    Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDatePicker = true }
                .semantics { contentDescription = dateSelectorCd },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = stringResource(com.yourname.expensetracker.R.string.date_label),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                stringResource(com.yourname.expensetracker.R.string.date_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                formattedDate,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDatePicker) {
        // RF-2b: state is scoped to the dialog so each opening seeds from the current dateMs;
        // a seed captured at first composition would go stale after quick-add Fill/reset and OK would revert the date.
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = UiTimeUtils.instantToPickerMillis(dateMs)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Preserve time of day, just change the date.
                            // G-TIME-01: pure derivation from the two existing
                            // timestamps (java.time, system default timezone) —
                            // same H/M/S carry-over and zero milliseconds the
                            // Calendar version produced. The picker value is
                            // UTC-midnight millis of the picked day, so it is
                            // converted via UiTimeUtils (not read in the system zone).
                            onDateSelected(UiTimeUtils.pickerMillisKeepingTimeOf(selectedDate, dateMs))
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(com.yourname.expensetracker.R.string.ok_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(com.yourname.expensetracker.R.string.cancel_button))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
