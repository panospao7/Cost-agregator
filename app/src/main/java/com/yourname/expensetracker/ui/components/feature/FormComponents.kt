package com.yourname.expensetracker.ui.components.feature

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.ui.util.UiTimeUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Standard text field for feature screen forms.
 */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isError: Boolean = false,
    supportingText: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = singleLine,
        enabled = enabled,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Standard amount field with numeric keyboard.
 */
@Composable
fun FormAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            // S2-001: Delegate to shared sanitizer — consistent across all money fields
            onValueChange(com.yourname.expensetracker.ui.util.AmountInputSanitizer.sanitize(raw))
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Standard dropdown field for feature screen forms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDropdown(
    value: String,
    onValueSelected: (String) -> Unit,
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    // warranty-edit: index of the currently selected option (-1 = no marker, the previous behavior).
    selectedIndex: Int = -1,
    // warranty-edit: called with the picked option's index, so callers can map by index, not label.
    onIndexSelected: (Int) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    // S2-017: Close menu if enabled becomes false
    androidx.compose.runtime.LaunchedEffect(enabled) { if (!enabled) expanded = false }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
            enabled = enabled
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                DropdownMenuItem(
                    text = { Text(option) },
                    leadingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = if (selectedIndex >= 0) {
                        Modifier.semantics { selected = isSelected }
                    } else {
                        Modifier
                    },
                    onClick = {
                        onValueSelected(option)
                        onIndexSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Standard date picker field for feature screen forms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDateField(
    dateMillis: Long,
    onDateSelected: (Long) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    // warranty-edit: limits the days the picker lets the user choose (default: any day).
    selectableDates: SelectableDates = DatePickerDefaults.AllDates,
    // warranty-edit: when true the whole field (not only the trailing icon) opens the picker, as one
    // merged button node described by fieldContentDescription (the icon becomes decorative).
    wholeFieldClickable: Boolean = false,
    fieldContentDescription: String? = null
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
    val formattedDate = dateFormat.format(Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault()))

    if (!wholeFieldClickable) {
        OutlinedTextField(
            value = formattedDate,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }, enabled = enabled) {
                    Icon(Icons.Default.CalendarToday, contentDescription = stringResource(R.string.a11y_select_date))
                }
            },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            modifier = modifier.fillMaxWidth(),
            enabled = enabled
        )
    } else {
        val clickLabel = stringResource(R.string.a11y_select_date)
        Box(modifier = modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = formattedDate,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null) },
                isError = isError,
                supportingText = supportingText?.let { { Text(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { },
                enabled = enabled
            )
            // Transparent click target over the field (the 48dp+ field height is the target).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .semantics(mergeDescendants = true) {
                        fieldContentDescription?.let { contentDescription = it }
                        if (isError && supportingText != null) error(supportingText)
                    }
                    .clickable(
                        enabled = enabled,
                        onClickLabel = clickLabel,
                        role = Role.Button
                    ) { showDatePicker = true }
            )
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = UiTimeUtils.instantToPickerMillis(dateMillis),
            selectableDates = selectableDates
        )
        
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            onDateSelected(UiTimeUtils.pickerMillisToLocalStartOfDay(it))
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * Standard dialog for adding/editing items in feature screens.
 */
@Composable
fun FormDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    isSubmitting: Boolean = false,
    confirmText: String = "",
    dismissText: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    val resolvedConfirm = confirmText.ifEmpty { stringResource(com.yourname.expensetracker.R.string.save_button) }
    val resolvedDismiss = dismissText.ifEmpty { stringResource(com.yourname.expensetracker.R.string.cancel_button) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                // S2-018R: Prevent double-submit
                enabled = confirmEnabled && !isSubmitting
            ) {
                if (isSubmitting) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                } else {
                    Text(resolvedConfirm)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(resolvedDismiss)
            }
        }
    )
}

/**
 * Standard section container for organizing form fields.
 */
@Composable
fun FormSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
        
        content()
    }
}

/**
 * Standard row of action buttons for forms.
 */
@Composable
fun FormActions(
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean = true,
    submitText: String = "",
    cancelText: String = "",
    modifier: Modifier = Modifier
) {
    val resolvedSubmit = submitText.ifEmpty { stringResource(com.yourname.expensetracker.R.string.save_button) }
    val resolvedCancel = cancelText.ifEmpty { stringResource(com.yourname.expensetracker.R.string.cancel_button) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text(resolvedCancel)
        }
        
        Button(
            onClick = onSubmit,
            enabled = submitEnabled,
            modifier = Modifier.weight(1f)
        ) {
            Text(resolvedSubmit)
        }
    }
}
