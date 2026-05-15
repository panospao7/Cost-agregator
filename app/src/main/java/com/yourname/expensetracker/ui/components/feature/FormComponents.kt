package com.yourname.expensetracker.ui.components.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
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
    enabled: Boolean = true
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
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
            enabled = enabled
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueSelected(option)
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
    enabled: Boolean = true
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.getDefault())
    
    OutlinedTextField(
        value = dateFormat.format(Instant.ofEpochMilli(dateMillis).atZone(ZoneId.systemDefault())),
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { showDatePicker = true }, enabled = enabled) {
                Icon(Icons.Default.CalendarToday, contentDescription = stringResource(R.string.a11y_select_date))
            }
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled
    )
    
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { onDateSelected(it) }
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
                enabled = confirmEnabled
            ) {
                Text(resolvedConfirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
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
