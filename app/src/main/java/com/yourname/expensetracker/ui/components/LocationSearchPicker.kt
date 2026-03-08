package com.yourname.expensetracker.ui.components

import android.preference.PreferenceManager
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.domain.location.GeocodingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reusable location search composable used by Features C (Transactions), D (Review),
 * and E (Map unlocated panel).
 *
 * Provides:
 *  - Search field with 1100 ms debounce that cascades through geocoding providers
 *  - Result list to pick from
 *  - "Advanced" toggle for manual lat/lon entry
 *  - "Clear location" button
 *
 * Callback [onResult] delivers (lat, lon, address, osmId) — all nullable (null lat/lon
 * means the user cleared the location).
 *
 * @param biasLat Optional latitude to bias search results toward (e.g. device location or map centre).
 * @param biasLon Optional longitude to bias search results toward.
 */
@Composable
fun LocationSearchPicker(
    currentLat: Double?,
    currentLon: Double?,
    currentAddress: String?,
    onResult: (lat: Double?, lon: Double?, address: String?, osmId: String?) -> Unit,
    modifier: Modifier = Modifier,
    geocodingService: GeocodingService,
    biasLat: Double? = null,
    biasLon: Double? = null
) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var manualLat by remember { mutableStateOf(currentLat?.toString() ?: "") }
    var manualLon by remember { mutableStateOf(currentLon?.toString() ?: "") }
    var latError by remember { mutableStateOf(false) }
    var lonError by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // Google Places toggle — off by default to conserve API quota.
    // User opts in when free-tier results don't show the specific branch they need.
    var useGoogle by remember { mutableStateOf(false) }
    // Tap-to-pin state: set when user long-presses the results map.
    var pinnedLat by remember { mutableStateOf<Double?>(null) }
    var pinnedLon by remember { mutableStateOf<Double?>(null) }
    var isPinResolving by remember { mutableStateOf(false) }
    var pinResult by remember { mutableStateOf<GeocodingResult?>(null) }

    // Helper to launch a debounced search with the current useGoogle flag
    fun launchSearch(query: String, withGoogle: Boolean) {
        searchJob?.cancel()
        if (query.length >= 2) {
            searchJob = scope.launch {
                delay(1100)
                isSearching = true
                searchError = null
                Log.d("LocationSearch", "==> Starting search for: $query (google=$withGoogle)")
                try {
                    val searchResults = withContext(Dispatchers.IO) {
                        geocodingService.searchMultiple(query, biasLat, biasLon, useGoogle = withGoogle)
                    }
                    Log.d("LocationSearch", "<== Got ${searchResults.size} results")
                    results = searchResults
                    if (searchResults.isEmpty()) {
                        searchError = "No results found"
                        Log.d("LocationSearch", "    No results found for: $query")
                    } else {
                        Log.d("LocationSearch", "    First result: ${searchResults.first().displayAddress}")
                    }
                } catch (e: CancellationException) {
                    Log.d("LocationSearch", "    Search cancelled (debounce)")
                    throw e
                } catch (e: Exception) {
                    Log.e("LocationSearch", "<== Search FAILED: ${e.message}", e)
                    results = emptyList()
                    searchError = "Search failed — check network"
                }
                isSearching = false
            }
        } else {
            results = emptyList()
            searchError = null
        }
    }

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
                launchSearch(query, useGoogle)
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

        // ── Google Places toggle chip ───────────────────────────────────────
        // Off by default to conserve quota. Tap to include Google Places in
        // the next search — useful when the free-tier services don't show
        // the specific branch you need.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp)
        ) {
            FilterChip(
                selected = useGoogle,
                onClick = {
                    val newValue = !useGoogle
                    useGoogle = newValue
                    // Re-fire search immediately with the new Google setting
                    // so the user doesn't have to retype.
                    if (searchQuery.length >= 2) {
                        launchSearch(searchQuery, newValue)
                    }
                },
                label = { Text("Google") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
            if (useGoogle) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "uses API quota",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Results list ────────────────────────────────────────────────────
        if (results.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(results) { result ->
                        // Compute distance from bias point (if available)
                        val distanceLabel = if (biasLat != null && biasLon != null) {
                            formatDistance(biasLat, biasLon, result.latitude, result.longitude)
                        } else null

                        // Headline: business name (bold). Fallback: first part of address.
                        val headline = result.name
                            ?: result.displayAddress?.substringBefore(",")?.trim()
                            ?: "%.5f, %.5f".format(result.latitude, result.longitude)

                        // Supporting text: full address (skip if it's the same as headline)
                        val supporting = result.displayAddress
                            ?.takeIf { it != headline }

                        ListItem(
                            headlineContent = {
                                Text(
                                    text = headline,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = supporting?.let { addr ->
                                {
                                    Text(
                                        text = addr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            trailingContent = distanceLabel?.let { dist ->
                                {
                                    Text(
                                        text = dist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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

        // ── Results map ─────────────────────────────────────────────────────
        if (results.isNotEmpty()) {
            ResultsMapView(
                results = results,
                onSelect = { result ->
                    onResult(result.latitude, result.longitude, result.displayAddress, result.osmId)
                    searchQuery = ""
                    results = emptyList()
                    pinResult = null
                },
                onLongPress = { lat, lon ->
                    // Drop a pin and reverse-geocode the tapped coordinate
                    pinnedLat = lat
                    pinnedLon = lon
                    pinResult = null
                    isPinResolving = true
                    scope.launch {
                        val resolved = withContext(Dispatchers.IO) {
                            geocodingService.reverseGeocode(lat, lon)
                        }
                        pinResult = resolved ?: GeocodingResult(
                            latitude = lat,
                            longitude = lon,
                            osmId = null,
                            name = null,
                            displayAddress = "%.5f, %.5f".format(lat, lon),
                            confidence = 1.0f,
                            source = "pin"
                        )
                        isPinResolving = false
                    }
                },
                pinnedLat = pinnedLat,
                pinnedLon = pinnedLon,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(top = 4.dp)
            )
            // B5: interaction hint below map
            Text(
                text = "Tap a marker to select · Long-press to drop a pin",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // ── Pin confirm card ────────────────────────────────────────────────
        if (isPinResolving) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Resolving address…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        pinResult?.let { pin ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.PinDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Pinned location",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pin.name ?: pin.displayAddress ?: "%.5f, %.5f".format(pin.latitude, pin.longitude),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pin.name != null && pin.displayAddress != null) {
                        Text(
                            text = pin.displayAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                pinResult = null
                                pinnedLat = null
                                pinnedLon = null
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Cancel") }
                        Button(
                            onClick = {
                                onResult(pin.latitude, pin.longitude, pin.displayAddress, pin.osmId)
                                searchQuery = ""
                                results = emptyList()
                                pinResult = null
                                pinnedLat = null
                                pinnedLon = null
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Use this location") }
                    }
                }
            }
        }

        // ── Error / empty state ────────────────────────────────────────────
        if (searchError != null && results.isEmpty() && !isSearching) {
            Text(
                text = searchError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
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

/**
 * Small osmdroid map that shows one marker per geocoding result.
 *
 * Fixes applied:
 *  - B1: setInfoWindow(null) on every marker — prevents the white-rectangle info bubble
 *  - B2: key-based diffing — overlays are only rebuilt when results/pin actually change,
 *        so in-progress touch events are never orphaned (fixes "taps do nothing")
 *  - B3: MapEventsOverlay stored in a stable remember ref — never lost on recomposition
 *  - B4: pin marker has setInfoWindow(null) + no-op click listener
 *  - B5: hint text rendered by the caller (see below map in LocationSearchPicker)
 *  - B6: bounding box includes the pinned coordinate when present
 */
@Suppress("DEPRECATION") // PreferenceManager.getDefaultSharedPreferences is fine for osmdroid config
@Composable
private fun ResultsMapView(
    results: List<GeocodingResult>,
    onSelect: (GeocodingResult) -> Unit,
    onLongPress: (lat: Double, lon: Double) -> Unit,
    pinnedLat: Double?,
    pinnedLon: Double?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, prefs)
        Configuration.getInstance().userAgentValue =
            com.yourname.expensetracker.domain.config.AppConfig.Location.NOMINATIM_USER_AGENT
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // B3: Store MapEventsOverlay in a stable ref so overlays.clear() can't lose it
    val eventsOverlayRef = remember { mutableStateOf<MapEventsOverlay?>(null) }

    // B2: Track last-rendered key — skip the update block if nothing changed so we
    //     don't tear down markers that are mid-touch-event
    val lastRenderKey = remember { mutableStateOf("") }

    // Stable lambda refs — callbacks captured once, updated via rememberUpdatedState
    val onLongPressRef = rememberUpdatedState(onLongPress)
    val onSelectRef = rememberUpdatedState(onSelect)

    AndroidView(
        factory = { ctx ->
            MapView(ctx).also { mv ->
                mv.setTileSource(TileSourceFactory.MAPNIK)
                mv.setMultiTouchControls(true)
                mv.setBuiltInZoomControls(false)

                // B3: Create the long-press overlay once and keep a stable reference
                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?) = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        if (p != null) onLongPressRef.value(p.latitude, p.longitude)
                        return true
                    }
                })
                eventsOverlayRef.value = eventsOverlay
                mv.overlays.add(0, eventsOverlay)
                mapViewRef.value = mv
            }
        },
        update = { mapView ->
            // B2: Build a key representing current data; skip rebuild if nothing changed
            val newKey = results.joinToString("|") { "${it.latitude},${it.longitude}" } +
                    "|$pinnedLat,$pinnedLon"
            if (newKey == lastRenderKey.value) return@AndroidView
            lastRenderKey.value = newKey

            // Rebuild overlays: keep MapEventsOverlay first (B3), then add markers
            mapView.overlays.clear()
            eventsOverlayRef.value?.let { mapView.overlays.add(0, it) }

            if (results.isEmpty()) {
                mapView.invalidate()
                return@AndroidView
            }

            // Add one marker per result
            // B1: no title/snippet + setInfoWindow(null) — prevents the white info bubble
            for (result in results) {
                val osmMarker = Marker(mapView).apply {
                    position = GeoPoint(result.latitude, result.longitude)
                    setInfoWindow(null)          // B1: disable default info window
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        onSelectRef.value(result)
                        true
                    }
                }
                mapView.overlays.add(osmMarker)
            }

            // B4: Pin marker — info window disabled, no-op click listener
            if (pinnedLat != null && pinnedLon != null) {
                val pinMarker = Marker(mapView).apply {
                    position = GeoPoint(pinnedLat, pinnedLon)
                    setInfoWindow(null)          // B4: disable default info window
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> true } // B4: consume tap, no action
                }
                mapView.overlays.add(pinMarker)
            }

            // B6: Bounding box includes pinned coordinate when present
            val allLats = results.map { it.latitude } +
                    listOfNotNull(pinnedLat)
            val allLons = results.map { it.longitude } +
                    listOfNotNull(pinnedLon)

            if (allLats.size == 1) {
                mapView.controller.setZoom(16.0)
                mapView.controller.setCenter(GeoPoint(allLats[0], allLons[0]))
            } else {
                val box = BoundingBox(
                    allLats.max(), allLons.max(),
                    allLats.min(), allLons.min()
                )
                mapView.post { mapView.zoomToBoundingBox(box, false, 80) }
            }

            mapView.invalidate()
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            mapViewRef.value?.let { mv ->
                mv.onPause()
                mv.onDetach()
            }
            mapViewRef.value = null
        }
    }
}

/**
 * Computes haversine distance between two lat/lon points and formats it
 * as a human-readable string: "350 m" for short distances, "3.2 km" for longer.
 */
private fun formatDistance(
    lat1: Double, lon1: Double,
    lat2: Double, lon2: Double
): String {
    val r = 6_371_000.0
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dPhi = Math.toRadians(lat2 - lat1)
    val dLambda = Math.toRadians(lon2 - lon1)
    val a = sin(dPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(dLambda / 2).let { it * it }
    val dist = r * 2 * atan2(sqrt(a), sqrt(1 - a))
    return when {
        dist < 1000 -> "${dist.roundToInt()} m"
        else -> "${"%.1f".format(dist / 1000)} km"
    }
}
