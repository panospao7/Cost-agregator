package com.yourname.expensetracker.ui.components

import android.annotation.SuppressLint
import android.preference.PreferenceManager
import android.view.MotionEvent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.location.GeocodingBatchResult
import com.yourname.expensetracker.domain.location.GeocodingLookupResult
import com.yourname.expensetracker.domain.location.GeocodingResult
import com.yourname.expensetracker.ui.screens.map.LocationPickerState
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import com.yourname.expensetracker.ui.theme.Dimens
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Legacy overload for callers that still pass [geocodingService] directly.
 * New callers should use the state+callbacks overload.
 * TODO: Migrate TransactionsScreen to use ViewModel-owned LocationPickerState.
 */
@Composable
fun LocationSearchPicker(
    currentLat: Double?,
    currentLon: Double?,
    currentAddress: String?,
    onResult: (lat: Double?, lon: Double?, address: String?, osmId: String?) -> Unit,
    modifier: Modifier = Modifier,
    geocodingService: com.yourname.expensetracker.domain.location.GeocodingService,
    biasLat: Double? = null,
    biasLon: Double? = null
) {
    val scope = rememberCoroutineScope()
    var pickerState by remember { mutableStateOf(LocationPickerState()) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchRequestId by remember { mutableStateOf(0L) }
    var pinRequestId by remember { mutableStateOf(0L) }

    // Propagate selection to caller
    LaunchedEffect(pickerState.pendingLat, pickerState.pendingLon) {
        if (pickerState.pendingLat != null) {
            onResult(pickerState.pendingLat, pickerState.pendingLon, pickerState.pendingAddress, pickerState.pendingOsmId)
        }
    }

    LocationSearchPicker(
        state = pickerState,
        onQueryChanged = { query, useGoogle ->
            pickerState = pickerState.copy(query = query, searchError = null)
            searchJob?.cancel()
            if (query.length < 2) { pickerState = pickerState.copy(results = emptyList(), isSearching = false); return@LocationSearchPicker }
            val id = ++searchRequestId
            searchJob = scope.launch {
                kotlinx.coroutines.delay(1100)
                if (id != searchRequestId) return@launch
                pickerState = pickerState.copy(isSearching = true)
                try {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        geocodingService.searchMultiple(query, biasLat, biasLon, useGoogle = useGoogle)
                    }
                    if (id != searchRequestId) return@launch
                    when (result) {
                        is GeocodingBatchResult.Success -> pickerState = pickerState.copy(results = result.results, isSearching = false, searchError = if (result.results.isEmpty()) "No results found" else null)
                        is GeocodingBatchResult.Failure -> pickerState = pickerState.copy(results = emptyList(), isSearching = false, searchError = "Search unavailable (${result.error})")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { if (id != searchRequestId) return@launch; pickerState = pickerState.copy(isSearching = false, searchError = "Search failed") }
            }
        },
        onResultSelected = { result ->
            pickerState = pickerState.copy(pendingLat = result.latitude, pendingLon = result.longitude, pendingAddress = result.displayAddress, pendingOsmId = result.osmId, results = emptyList())
        },
        onMapLongPressed = { lat, lon ->
            val id = ++pinRequestId
            pickerState = pickerState.copy(pinnedLat = lat, pinnedLon = lon, isPinResolving = true, pinResult = null)
            scope.launch {
                try {
                    val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { geocodingService.reverseGeocode(lat, lon) }
                    if (id != pinRequestId) return@launch
                    val r = when (resolved) {
                        is com.yourname.expensetracker.domain.location.GeocodingLookupResult.Success -> resolved.result
                        is com.yourname.expensetracker.domain.location.GeocodingLookupResult.Failure -> null
                    } ?: GeocodingResult(latitude = lat, longitude = lon, osmId = null, name = null, displayAddress = "%.5f, %.5f".format(lat, lon), confidence = 1.0f, source = "pin")
                    pickerState = pickerState.copy(isPinResolving = false, pinResult = r)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { if (id != pinRequestId) return@launch; pickerState = pickerState.copy(isPinResolving = false) }
            }
        },
        onPinConfirmed = {
            val pin = pickerState.pinResult ?: return@LocationSearchPicker
            pickerState = pickerState.copy(pendingLat = pin.latitude, pendingLon = pin.longitude, pendingAddress = pin.displayAddress, pendingOsmId = pin.osmId, pinnedLat = null, pinnedLon = null, pinResult = null)
        },
        onPinCancelled = { pickerState = pickerState.copy(pinnedLat = null, pinnedLon = null, pinResult = null) },
        onCleared = { pickerState = LocationPickerState(); onResult(null, null, null, null) },
        modifier = modifier,
        currentLat = currentLat,
        currentLon = currentLon,
        currentAddress = currentAddress,
        biasLat = biasLat,
        biasLon = biasLon
    )
}

/**
 * Pure UI location search composable. All search/reverse-geocode logic is owned
 * by the caller's ViewModel via [LocationPickerState] and callbacks.
 *
 * S10-001/S10-020: No GeocodingService, no coroutines, no network calls here.
 *
 * @param state Current picker state from ViewModel.
 * @param onQueryChanged Called when search text or Google toggle changes.
 * @param onResultSelected Called when user picks a result from the list.
 * @param onMapLongPressed Called when user long-presses the map to drop a pin.
 * @param onPinConfirmed Called when user confirms the dropped pin.
 * @param onPinCancelled Called when user cancels the dropped pin.
 * @param onCleared Called when user taps the clear-location button.
 * @param biasLat Optional latitude to bias map default centre.
 * @param biasLon Optional longitude to bias map default centre.
 */
@Composable
fun LocationSearchPicker(
    state: com.yourname.expensetracker.ui.screens.map.LocationPickerState,
    onQueryChanged: (query: String, useGoogle: Boolean) -> Unit,
    onResultSelected: (com.yourname.expensetracker.domain.location.GeocodingResult) -> Unit,
    onMapLongPressed: (lat: Double, lon: Double) -> Unit,
    onPinConfirmed: () -> Unit,
    onPinCancelled: () -> Unit,
    onCleared: () -> Unit,
    modifier: Modifier = Modifier,
    currentLat: Double? = null,
    currentLon: Double? = null,
    currentAddress: String? = null,
    biasLat: Double? = null,
    biasLon: Double? = null
) {
    var useGoogle by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var manualLat by remember { mutableStateOf(currentLat?.toString() ?: "") }
    var manualLon by remember { mutableStateOf(currentLon?.toString() ?: "") }
    var latError by remember { mutableStateOf(false) }
    var lonError by remember { mutableStateOf(false) }

    // Auto-expand map when results arrive
    LaunchedEffect(state.results) {
        if (state.results.isNotEmpty()) showMap = true
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
                    onClick = onCleared,
                    modifier = Modifier.size(Dimens.TouchTargetMin)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.a11y_clear_location),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // ── Search field ────────────────────────────────────────────────────
        OutlinedTextField(
            value = state.query,
            onValueChange = { query -> onQueryChanged(query, useGoogle) },
            label = { Text(stringResource(R.string.location_search_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (state.isSearching) {
                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else if (state.query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChanged("", useGoogle) }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.a11y_clear_search))
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // ── Google Places toggle chip ───────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp)
        ) {
            FilterChip(
                selected = useGoogle,
                onClick = {
                    val newValue = !useGoogle
                    useGoogle = newValue
                    if (state.query.length >= 2) onQueryChanged(state.query, newValue)
                },
                label = { Text(stringResource(R.string.location_google_label)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            if (useGoogle) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.location_google_quota_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Results list ────────────────────────────────────────────────────
        if (state.results.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(state.results) { result ->
                        val distanceLabel = if (biasLat != null && biasLon != null) {
                            formatDistance(biasLat, biasLon, result.latitude, result.longitude)
                        } else null
                        val headline = result.name
                            ?: result.displayAddress?.substringBefore(",")?.trim()
                            ?: "%.5f, %.5f".format(result.latitude, result.longitude)
                        val supporting = result.displayAddress?.takeIf { it != headline }
                        ListItem(
                            headlineContent = {
                                Text(text = headline, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = supporting?.let { addr ->
                                { Text(text = addr, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            },
                            leadingContent = { Icon(imageVector = Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = distanceLabel?.let { dist ->
                                { Text(text = dist, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            },
                            modifier = Modifier.clickable { onResultSelected(result) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        // ── Map toggle ──────────────────────────────────────────────────────
        TextButton(onClick = { showMap = !showMap }, modifier = Modifier.padding(top = 2.dp)) {
            Text(if (showMap) stringResource(R.string.location_hide_map) else stringResource(R.string.location_show_map))
        }

        if (showMap) {
            ResultsMapView(
                results = state.results,
                defaultCentre = GeoPoint(biasLat ?: 37.9838, biasLon ?: 23.7275),
                onSelect = { result -> onResultSelected(result) },
                onLongPress = onMapLongPressed,
                pinnedLat = state.pinnedLat,
                pinnedLon = state.pinnedLon,
                modifier = Modifier.fillMaxWidth().height(260.dp).padding(top = 4.dp)
            )
            Text(
                text = stringResource(R.string.location_map_interaction_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // ── Pin resolving indicator ─────────────────────────────────────────
        if (state.isPinResolving) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(text = stringResource(R.string.location_resolving_address),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── Pin confirm card ────────────────────────────────────────────────
        state.pinResult?.let { pin ->
            Card(modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Filled.PinDrop, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = stringResource(R.string.location_pinned_location),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(text = pin.name ?: pin.displayAddress ?: "%.5f, %.5f".format(pin.latitude, pin.longitude),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pin.name != null && pin.displayAddress != null) {
                        Text(text = pin.displayAddress, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPinCancelled, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Button(onClick = onPinConfirmed, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.location_use_this_location))
                        }
                    }
                }
            }
        }

        // ── Error state ─────────────────────────────────────────────────────
        if (state.searchError != null && state.results.isEmpty() && !state.isSearching) {
            Text(text = state.searchError, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }

        // ── Advanced toggle ─────────────────────────────────────────────────
        TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (showAdvanced) stringResource(R.string.location_advanced_hide) else stringResource(R.string.location_advanced_show))
        }

        if (showAdvanced) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = manualLat, onValueChange = { manualLat = it; latError = false },
                    label = { Text(stringResource(R.string.location_latitude_label)) }, isError = latError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = manualLon, onValueChange = { manualLon = it; lonError = false },
                    label = { Text(stringResource(R.string.location_longitude_label)) }, isError = lonError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), singleLine = true)
            }
            Button(
                onClick = {
                    val lat = manualLat.toDoubleOrNull()
                    val lon = manualLon.toDoubleOrNull()
                    latError = lat == null || lat !in -90.0..90.0
                    lonError = lon == null || lon !in -180.0..180.0
                    if (!latError && !lonError) {
                        onResultSelected(com.yourname.expensetracker.domain.location.GeocodingResult(
                            latitude = lat!!, longitude = lon!!, osmId = null, name = null,
                            displayAddress = null, confidence = 1.0f, source = "manual"
                        ))
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(stringResource(R.string.location_set_coordinates)) }
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
 *  - B9: requestDisallowInterceptTouchEvent so parent scroll/sheet doesn't steal map touches
 *  - B10: zoomToBoundingBox deferred with addOnLayoutChangeListener for reliable sizing
 */
@SuppressLint("ClickableViewAccessibility")
@Suppress("DEPRECATION") // PreferenceManager.getDefaultSharedPreferences is fine for osmdroid config
@Composable
private fun ResultsMapView(
    results: List<GeocodingResult>,
    defaultCentre: GeoPoint,   // F1: used to centre the map when no results exist yet
    onSelect: (GeocodingResult) -> Unit,
    onLongPress: (lat: Double, lon: Double) -> Unit,
    pinnedLat: Double?,
    pinnedLon: Double?,
    modifier: Modifier = Modifier
) {
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
            // F4: Load osmdroid config synchronously before MapView construction so
            //     tile cache paths are available when the MapView is first created.
            Configuration.getInstance().load(ctx, android.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
            Configuration.getInstance().userAgentValue =
                com.yourname.expensetracker.domain.config.AppConfig.Location.NOMINATIM_USER_AGENT

            MapView(ctx).also { mv ->
                mv.setTileSource(TileSourceFactory.MAPNIK)
                mv.setMultiTouchControls(true)
                mv.setBuiltInZoomControls(false)
                mv.onResume()  // F5: start tile-download threads immediately

                // B9: Tell parent containers (ModalBottomSheet, verticalScroll, etc.)
                // to stop intercepting touch events when the user is interacting with
                // the map. Without this, vertical drags are stolen by the sheet/scroll
                // and the map cannot be panned or zoomed.
                mv.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    // Return false so osmdroid still processes the event normally
                    false
                }

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
                // F1: No results yet — show the default centre so the map is useful
                //     immediately (user can browse and long-press to drop a pin).
                mapView.controller.setZoom(13.0)
                mapView.controller.setCenter(defaultCentre)
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
                // B10: Use post with a layout-ready check instead of bare post.
                // The MapView may not have measured yet when update{} fires for the
                // first time (especially inside ModalBottomSheet / AlertDialog).
                // If width/height are 0, defer until the next layout pass.
                if (mapView.width > 0 && mapView.height > 0) {
                    mapView.post { mapView.zoomToBoundingBox(box, true, 80) }
                } else {
                    mapView.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            v: android.view.View, l: Int, t: Int, r: Int, b: Int,
                            ol: Int, ot: Int, or2: Int, ob: Int
                        ) {
                            if (r - l > 0 && b - t > 0) {
                                mapView.removeOnLayoutChangeListener(this)
                                mapView.post { mapView.zoomToBoundingBox(box, true, 80) }
                            }
                        }
                    })
                }
            }

            mapView.invalidate()
        },
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
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
