package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.ui.screens.map.LocationPickerState

/**
 * Bottom sheet for correcting a merchant pin location.
 * S10-001/S10-020: Uses ViewModel-owned [LocationPickerState] — no GeocodingService in UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationCorrectionSheet(
    merchantName: String,
    initialLat: Double?,
    initialLon: Double?,
    pickerState: LocationPickerState,
    onQueryChanged: (String, Boolean) -> Unit,
    onResultSelected: (GeocodingResult) -> Unit,
    onMapLongPressed: (Double, Double) -> Unit,
    onPinConfirmed: () -> Unit,
    onPinCancelled: () -> Unit,
    onLocationCleared: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (lat: Double, lon: Double, address: String?, osmId: String?) -> Unit
) {
    val hasSelection = pickerState.hasSelection

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // B9: Open fully expanded so the map + search picker have enough room
        // without needing a scroll container that would steal map touch events.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // B9: NO verticalScroll() — the scroll modifier intercepts vertical
                // touch events and makes the embedded osmdroid MapView un-pannable.
                // The sheet is fully expanded, so all content is visible without scrolling.
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.location_correct_title),
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
                state = pickerState,
                onQueryChanged = onQueryChanged,
                onResultSelected = onResultSelected,
                onMapLongPressed = onMapLongPressed,
                onPinConfirmed = onPinConfirmed,
                onPinCancelled = onPinCancelled,
                onCleared = onLocationCleared,
                currentLat = if (hasSelection) pickerState.pendingLat else initialLat,
                currentLon = if (hasSelection) pickerState.pendingLon else initialLon,
                currentAddress = if (hasSelection) pickerState.pendingAddress else null,
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
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val lat = pickerState.pendingLat ?: return@Button
                        val lon = pickerState.pendingLon ?: return@Button
                        onConfirm(lat, lon, pickerState.pendingAddress, pickerState.pendingOsmId)
                    },
                    enabled = hasSelection,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}
