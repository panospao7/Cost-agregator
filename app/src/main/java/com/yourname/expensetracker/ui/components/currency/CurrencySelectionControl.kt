package com.yourname.expensetracker.ui.components.currency

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import java.text.Normalizer
import java.util.Currency
import java.util.Locale

/**
 * manual-entry-currency (ux-flows section 2): the shared currency control. A field-style button showing
 * symbol + code that opens a [ModalBottomSheet] with search and a list (home header first, the others by
 * code). The caller supplies the already-filtered [options] (CurrencySelectionPolicy, owned by the view
 * model): currencies that are not selectable are omitted, never greyed. Holds only the sheet-open flag and
 * the search text in plain Compose state; nothing here touches amounts, storage or logs.
 */
@Composable
fun CurrencySelectionControl(
    selectedCode: String?,
    options: List<String>,
    homeCode: String?,
    enabled: Boolean,
    isUnset: Boolean,
    readOnly: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    openRequest: Int = 0,
    onOpenRequestConsumed: () -> Unit = {}
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val currentOnOpenRequestConsumed by rememberUpdatedState(onOpenRequestConsumed)

    // "Change currency" on the blocked card asks the control to open its sheet: a counter bumped by the host.
    // The request is one-shot: once handled (opened, or dropped because the control cannot open) the host is
    // told to reset it, so a layout change that re-runs this effect in a new slot cannot reopen the picker.
    LaunchedEffect(openRequest) {
        if (openRequest > 0) {
            if (enabled && !readOnly) sheetOpen = true
            currentOnOpenRequestConsumed()
        }
    }

    val code = selectedCode.orEmpty()
    val label = when {
        code.isBlank() -> stringResource(R.string.mec_control_label)
        else -> controlLabel(code)
    }

    if (readOnly) {
        val readOnlyCd = stringResource(R.string.mec_control_readonly_cd, code)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = modifier
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) { contentDescription = readOnlyCd }
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val unsetText = stringResource(R.string.mec_control_unset)
    val changeCd = if (isUnset || code.isBlank()) {
        if (isUnset) unsetText else label
    } else {
        stringResource(R.string.mec_control_cd, spokenCurrency(code))
    }
    val showUnset = isUnset
    OutlinedButton(
        onClick = { sheetOpen = true },
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .focusRequester(focusRequester)
            .semantics {
                contentDescription = changeCd
                role = Role.Button
            }
    ) {
        Text(
            text = if (showUnset) unsetText else label,
            color = if (showUnset) MaterialTheme.colorScheme.error else Color.Unspecified,
            style = MaterialTheme.typography.bodyLarge
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
    }

    if (sheetOpen) {
        CurrencyPickerSheet(
            options = options,
            homeCode = homeCode,
            selectedCode = selectedCode,
            onSelect = { picked ->
                sheetOpen = false
                restoreFocus = true
                onSelect(picked)
            },
            onDismiss = {
                sheetOpen = false
                restoreFocus = true
            }
        )
    }

    // ux-flows section 2: focus returns to the control after the sheet closes.
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) {
            restoreFocus = false
            try {
                focusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // The control left composition meanwhile (for example the host navigated); nothing to focus.
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyPickerSheet(
    options: List<String>,
    homeCode: String?,
    selectedCode: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val rows = remember(options) { options.map { PickerRow(it, currencySymbolFor(it), currencyDisplayName(it)) } }
    val needle = remember(query) { foldForSearch(query.trim()) }
    val matches = remember(rows, needle) {
        if (needle.isEmpty()) rows else rows.filter { foldForSearch(it.code).contains(needle) || foldForSearch(it.name).contains(needle) }
    }
    val homeRow = matches.firstOrNull { it.code == homeCode }
    val others = remember(matches, homeCode) { matches.filter { it.code != homeCode }.sortedBy { it.code } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.imePadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.mec_control_unset),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_content_description))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.mec_sheet_search)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (homeRow != null) {
                    item(key = "header-home") { PickerHeader(stringResource(R.string.mec_sheet_home)) }
                    item(key = "row-" + homeRow.code) {
                        PickerRowItem(homeRow, homeRow.code == selectedCode) { onSelect(homeRow.code) }
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "header-other") { PickerHeader(stringResource(R.string.mec_sheet_other)) }
                    items(others, key = { "row-" + it.code }) { row ->
                        PickerRowItem(row, row.code == selectedCode) { onSelect(row.code) }
                    }
                }
                if (homeRow == null && others.isEmpty()) {
                    item(key = "no-results") {
                        Text(
                            stringResource(R.string.mec_sheet_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(16.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun PickerRowItem(row: PickerRow, isSelected: Boolean, onClick: () -> Unit) {
    val selectedText = stringResource(R.string.add_expense_selected)
    val spoken = if (row.name.isBlank() || row.name == row.code) row.code else row.code + ", " + row.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                selected = isSelected
                if (isSelected) stateDescription = selectedText
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            row.symbol,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(row.code, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (row.name.isNotBlank() && row.name != row.code) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private data class PickerRow(val code: String, val symbol: String, val name: String)

private fun controlLabel(code: String): String {
    val symbol = currencySymbolFor(code)
    return if (symbol.isBlank() || symbol == code) code else "$symbol $code"
}

/** Spoken form for the control description: "US dollar, USD". */
private fun spokenCurrency(code: String): String {
    val name = currencyDisplayName(code)
    return if (name.isBlank() || name == code) code else "$name, $code"
}

/** The localized platform display name of [code], or the code itself when the platform does not know it. */
fun currencyDisplayName(code: String): String =
    try {
        Currency.getInstance(code).getDisplayName(Locale.getDefault())
    } catch (e: IllegalArgumentException) {
        code
    }

/**
 * The currency symbol for [code] derived from the shared money formatter (same rule the amount field
 * used for the home currency before manual-entry-currency). Blank for a null or blank code.
 */
fun currencySymbolFor(code: String?): String {
    val currency = code.orEmpty()
    if (currency.isBlank()) return ""
    return CurrencyFormatter.formatMoney(0.0, currency, showCents = false)
        .replace(Regex("[0-9\\s.,]"), "")
        .ifBlank { CurrencyFormatter.formatMoney(1.0, currency, showCents = false).replace(Regex("[0-9\\s.,]"), "") }
}

private val combiningMarks = Regex("\\p{M}+")

/** Case- and accent-insensitive folding for the search field (ux-flows section 2). */
private fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.getDefault())
