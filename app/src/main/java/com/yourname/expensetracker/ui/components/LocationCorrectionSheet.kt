package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet that lets the user manually enter corrected coordinates for a
 * merchant pin on the spending map.
 *
 * The sheet is intentionally simple: lat/lon text fields plus a confirm button.
 * A future iteration could embed a small interactive map for drag-to-place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationCorrectionSheet(
    merchantName: String,
    initialLat: Double?,
    initialLon: Double?,
    onDismiss: () -> Unit,
    onConfirm: (lat: Double, lon: Double, address: String?) -> Unit
) {
    var latText by remember { mutableStateOf(initialLat?.toString() ?: "") }
    var lonText by remember { mutableStateOf(initialLon?.toString() ?: "") }
    var addressText by remember { mutableStateOf("") }
    var latError by remember { mutableStateOf(false) }
    var lonError by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Correct location",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Merchant: $merchantName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = latText,
                onValueChange = {
                    latText = it
                    latError = false
                },
                label = { Text("Latitude") },
                isError = latError,
                supportingText = if (latError) ({ Text("Enter a valid latitude (-90 to 90)") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = lonText,
                onValueChange = {
                    lonText = it
                    lonError = false
                },
                label = { Text("Longitude") },
                isError = lonError,
                supportingText = if (lonError) ({ Text("Enter a valid longitude (-180 to 180)") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                label = { Text("Address (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        val lat = latText.toDoubleOrNull()
                        val lon = lonText.toDoubleOrNull()
                        val validLat = lat != null && lat in -90.0..90.0
                        val validLon = lon != null && lon in -180.0..180.0
                        latError = !validLat
                        lonError = !validLon
                        if (validLat && validLon) {
                            onConfirm(lat!!, lon!!, addressText.ifBlank { null })
                        }
                    }
                ) {
                    Text("Save")
                }
            }
        }
    }
}
