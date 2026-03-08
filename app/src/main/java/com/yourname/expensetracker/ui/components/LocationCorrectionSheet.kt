package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.domain.location.GeocodingService

/**
 * Bottom sheet that lets the user search for and correct the location of a
 * merchant pin on the spending map.
 *
 * Upgraded from raw lat/lon text fields to the full [LocationSearchPicker]
 * so the correction experience is consistent with EditLocationDialog (Transactions),
 * EditReviewDialog (Review), and PinExpenseSheet (Map unlocated panel).
 *
 * @param geocodingService Used by [LocationSearchPicker] for search + reverse geocoding.
 * @param onConfirm Delivers (lat, lon, address, osmId) when the user confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationCorrectionSheet(
    merchantName: String,
    initialLat: Double?,
    initialLon: Double?,
    geocodingService: GeocodingService,
    onDismiss: () -> Unit,
    onConfirm: (lat: Double, lon: Double, address: String?, osmId: String?) -> Unit
) {
    // Staged selection — null until the user picks something in the picker
    var pendingLat by remember { mutableStateOf<Double?>(null) }
    var pendingLon by remember { mutableStateOf<Double?>(null) }
    var pendingAddress by remember { mutableStateOf<String?>(null) }
    var pendingOsmId by remember { mutableStateOf<String?>(null) }
    val hasSelection = pendingLat != null && pendingLon != null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Text(
                text = "Correct location",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = merchantName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ── Location picker ──────────────────────────────────────────────
            LocationSearchPicker(
                currentLat = if (hasSelection) pendingLat else initialLat,
                currentLon = if (hasSelection) pendingLon else initialLon,
                currentAddress = if (hasSelection) pendingAddress else null,
                onResult = { lat, lon, address, osmId ->
                    pendingLat = lat
                    pendingLon = lon
                    pendingAddress = address
                    pendingOsmId = osmId
                },
                geocodingService = geocodingService,
                biasLat = initialLat,
                biasLon = initialLon
            )

            // ── Actions ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val lat = pendingLat ?: return@Button
                        val lon = pendingLon ?: return@Button
                        onConfirm(lat, lon, pendingAddress, pendingOsmId)
                    },
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}
