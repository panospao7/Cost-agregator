package com.yourname.expensetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yourname.expensetracker.data.location.NominatimGeocodingService
import com.yourname.expensetracker.domain.location.GeocodingResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reusable location search composable used by Features C (Transactions), D (Review),
 * and E (Map unlocated panel).
 *
 * Provides:
 *  - Search field with 500 ms debounce that calls Nominatim
 *  - Result list to pick from
 *  - "Advanced" toggle for manual lat/lon entry
 *  - "Clear location" button
 *
 * Callback [onResult] delivers (lat, lon, address, osmId) — all nullable (null lat/lon
 * means the user cleared the location).
 */
@Composable
fun LocationSearchPicker(
    currentLat: Double?,
    currentLon: Double?,
    currentAddress: String?,
    onResult: (lat: Double?, lon: Double?, address: String?, osmId: String?) -> Unit,
    modifier: Modifier = Modifier,
    geocodingService: NominatimGeocodingService? = null
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var manualLat by remember { mutableStateOf(currentLat?.toString() ?: "") }
    var manualLon by remember { mutableStateOf(currentLon?.toString() ?: "") }
    var latError by remember { mutableStateOf(false) }
    var lonError by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Current location chip ───────────────────────────────────────────
        if (currentLat != null && currentLon != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = currentAddress ?: "%.5f, %.5f".format(currentLat, currentLon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onResult(null, null, null, null) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = "Clear location",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // ── Search field ────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { query ->
                searchQuery = query
                searchJob?.cancel()
                if (query.length >= 2) {
                    searchJob = scope.launch {
                        delay(500)
                        isSearching = true
                        results = try {
                            // Use provided service or create one on the fly
                            val service = geocodingService ?: NominatimGeocodingService()
                            val result = service.search(query)
                            if (result != null) listOf(result) else emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                        isSearching = false
                    }
                } else {
                    results = emptyList()
                }
            },
            label = { Text("Search location") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (isSearching) {
                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = {
                        searchQuery = ""
                        results = emptyList()
                    }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // ── Results list ────────────────────────────────────────────────────
        if (results.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(results) { result ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = result.displayAddress ?: "%.5f, %.5f".format(result.latitude, result.longitude),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable {
                                onResult(result.latitude, result.longitude, result.displayAddress, result.osmId)
                                searchQuery = ""
                                results = emptyList()
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        // ── Advanced toggle ─────────────────────────────────────────────────
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(if (showAdvanced) "Hide manual coordinates" else "Enter coordinates manually")
        }

        if (showAdvanced) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualLat,
                    onValueChange = { manualLat = it; latError = false },
                    label = { Text("Latitude") },
                    isError = latError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = manualLon,
                    onValueChange = { manualLon = it; lonError = false },
                    label = { Text("Longitude") },
                    isError = lonError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            Button(
                onClick = {
                    val lat = manualLat.toDoubleOrNull()
                    val lon = manualLon.toDoubleOrNull()
                    latError = lat == null || lat !in -90.0..90.0
                    lonError = lon == null || lon !in -180.0..180.0
                    if (!latError && !lonError) {
                        onResult(lat, lon, null, null)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Set coordinates")
            }
        }
    }
}
