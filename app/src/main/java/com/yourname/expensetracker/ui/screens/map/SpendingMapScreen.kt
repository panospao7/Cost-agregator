package com.yourname.expensetracker.ui.screens.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.ui.components.LocationCorrectionSheet
import com.yourname.expensetracker.ui.components.LocationPermissionDialog
import com.yourname.expensetracker.ui.components.LocationSearchPicker
import com.yourname.expensetracker.ui.components.NearbyShopSuggestionCard
import com.yourname.expensetracker.ui.components.PlaceInsightCard
import com.yourname.expensetracker.ui.components.common.ListSkeleton
import com.yourname.expensetracker.ui.theme.SemanticColors
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import kotlin.math.max

/**
 * Tab 5 — Spending Map screen.
 *
 * Shows:
 *  - OpenStreetMap base map via osmdroid
 *  - Heat-coloured circle overlays for spending intensity
 *  - Expense markers (tappable)
 *  - Bottom sheet: marker detail + re-resolve / correct-pin actions
 *  - Overpass candidate list when automatic resolution is ambiguous
 *  - Place-insights card list below the map
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingMapScreen(
    initialLocationQuery: String? = null,
    viewModel: SpendingMapViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(initialLocationQuery) {
        viewModel.focusOnMerchant(initialLocationQuery)
    }

    // ── Permission launcher ───────────────────────────────────────────────────
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        viewModel.onPermissionResult(granted)
    }

    // Check current permission status once
    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val alreadyGranted = fine == PackageManager.PERMISSION_GRANTED ||
                             coarse == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionResult(alreadyGranted)
        if (!alreadyGranted) {
            viewModel.onShowPermissionRationale(true)
        }
    }

    // ── Snackbar ──────────────────────────────────────────────────────────────
    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.onSnackbarDismissed()
    }

    // ── Permission dialog ─────────────────────────────────────────────────────
    LocationPermissionDialog(
        showDialog = state.showPermissionRationale,
        onDismiss = { viewModel.onShowPermissionRationale(false) },
        onGrant = {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    )

    // ── Correction sheet ──────────────────────────────────────────────────────
    if (state.showCorrectionSheet) {
        val marker = state.selectedMarker
        if (marker != null) {
            LocationCorrectionSheet(
                merchantName = state.pendingCorrectionMerchant ?: marker.merchant,
                initialLat = state.pendingCorrectionLat ?: marker.latitude,
                initialLon = state.pendingCorrectionLon ?: marker.longitude,
                geocodingService = viewModel.geocodingService,
                onDismiss = { viewModel.onCloseCorrectionSheet() },
                onConfirm = { lat, lon, address, osmId ->
                    viewModel.onSaveCorrection(
                        merchantName = marker.merchant,
                        correctedLat = lat,
                        correctedLon = lon,
                        osmId = osmId ?: state.pendingCorrectionOsmId,
                        displayAddress = address,
                        forMarker = marker
                    )
                }
            )
        }
    }

    // ── Pin expense sheet (Feature E) ─────────────────────────────────────────
    val pinExpense = state.expenseToPin
    if (pinExpense != null) {
        PinExpenseSheet(
            expense = pinExpense,
            onDismiss = { viewModel.onDismissPinSheet() },
            onSave = { lat, lon, address, osmId ->
                viewModel.assignLocationToExpense(pinExpense, lat, lon, address, osmId)
            },
            geocodingService = viewModel.geocodingService,
            deviceLat = state.deviceLatitude,
            deviceLon = state.deviceLongitude
        )
    }

    Scaffold(
        containerColor = SemanticColors.BaseNavy,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Map area skeleton
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.55f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Stats bar skeleton
                ListSkeleton(itemCount = 3)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Filters ───────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.highlightedMerchantQuery?.let { highlightedMerchant ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.map_focused_location),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = highlightedMerchant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = { viewModel.focusOnMerchant(null) }) {
                                Text(stringResource(R.string.action_close))
                            }
                        }
                    }
                }

                val now = System.currentTimeMillis()
                val sevenDaysStart = now - 7L * 86_400_000L
                val thirtyDaysStart = now - 30L * 86_400_000L
                val ninetyDaysStart = now - 90L * 86_400_000L

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.map_filter_date_range),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.selectedCategories.isNotEmpty() || state.selectedDateRangePreset != null || state.dateRangeStartMs != null || state.dateRangeEndMs != null) {
                        TextButton(onClick = viewModel::clearFilters) {
                            Text(stringResource(R.string.map_filter_clear))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.selectedDateRangePreset == null && state.dateRangeStartMs == null && state.dateRangeEndMs == null,
                        onClick = { viewModel.setDateRange(null, null, null) },
                        label = { Text(stringResource(R.string.map_filter_all_dates)) }
                    )
                    FilterChip(
                        selected = state.selectedDateRangePreset == DateRangePreset.LAST_7_DAYS,
                        onClick = { viewModel.setDateRange(sevenDaysStart, now, DateRangePreset.LAST_7_DAYS) },
                        label = { Text(stringResource(R.string.map_filter_7_days)) }
                    )
                    FilterChip(
                        selected = state.selectedDateRangePreset == DateRangePreset.LAST_30_DAYS,
                        onClick = { viewModel.setDateRange(thirtyDaysStart, now, DateRangePreset.LAST_30_DAYS) },
                        label = { Text(stringResource(R.string.map_filter_30_days)) }
                    )
                    FilterChip(
                        selected = state.selectedDateRangePreset == DateRangePreset.LAST_90_DAYS,
                        onClick = { viewModel.setDateRange(ninetyDaysStart, now, DateRangePreset.LAST_90_DAYS) },
                        label = { Text(stringResource(R.string.map_filter_90_days)) }
                    )
                }

                if (state.availableCategories.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.map_filter_categories),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.availableCategories) { category ->
                            FilterChip(
                                selected = state.selectedCategories.contains(category.key),
                                onClick = { viewModel.toggleCategoryFilter(category.key) },
                                label = {
                                    Text(category.label)
                                }
                            )
                        }
                    }
                }
            }

            // ── Map ───────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)  // ~55% of screen height
            ) {
                // F3: Flag to request a one-shot re-centre on the device location.
                var centreOnDeviceRequest by remember { mutableStateOf(false) }

                OsmMapView(
                    markers = state.markers,
                    heatmapPoints = state.heatmapPoints,
                    deviceLat = state.deviceLatitude,
                    deviceLon = state.deviceLongitude,
                    centreOnDeviceRequest = centreOnDeviceRequest,
                    onCentreHandled = { centreOnDeviceRequest = false },
                    onMarkerClick = { viewModel.onMarkerSelected(it) }
                )

                // Re-centre on device location button
                if (state.locationPermissionGranted &&
                    state.deviceLatitude != null && state.deviceLongitude != null) {
                    val centerLocationCd = stringResource(R.string.map_center_my_location_cd)
                    FloatingActionButton(
                        onClick = { centreOnDeviceRequest = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .semantics { contentDescription = centerLocationCd },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                    }
                } else if (!state.locationPermissionGranted) {
                    val enableLocationCd = stringResource(R.string.map_enable_location_cd)
                    FloatingActionButton(
                        onClick = { viewModel.onShowPermissionRationale(true) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .semantics { contentDescription = enableLocationCd },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(Icons.Default.LocationSearching, contentDescription = null)
                    }
                }
            }

            // ── Selected-marker detail strip ──────────────────────────────────
            AnimatedVisibility(
                visible = state.selectedMarker != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                state.selectedMarker?.let { marker ->
                    MarkerDetailCard(
                        marker = marker,
                        isResolvingLocation = state.isResolvingLocation,
                        onReResolve = { viewModel.onResolveLocationForMarker(marker) },
                        onCorrectPin = { viewModel.onOpenCorrectionSheet(marker) },
                        onDismiss = { viewModel.onMarkerSelected(null) }
                    )
                }
            }

            // ── Overpass candidates ───────────────────────────────────────────
            AnimatedVisibility(visible = state.overpassCandidates.isNotEmpty()) {
                val marker = state.selectedMarker
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.map_multiple_shops),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    state.overpassCandidates.forEach { poi ->
                        NearbyShopSuggestionCard(
                            poi = poi,
                            onSelect = {
                                if (marker != null) viewModel.onPoiSelected(poi, marker)
                            },
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }

            // ── Stats bar ─────────────────────────────────────────────────────
            if (state.overpassCandidates.isEmpty() && state.selectedMarker == null) {
                LocationStatsBar(
                    located = state.totalLocatedExpenses,
                    unlocated = state.totalUnlocatedExpenses
                )
            }

            // ── Unlocated expenses panel (Feature E) ──────────────────────────
            if (state.unlocatedExpenses.isNotEmpty() &&
                state.overpassCandidates.isEmpty() &&
                state.selectedMarker == null) {
                UnlocatedExpensesPanel(
                    expenses = state.unlocatedExpenses,
                    onPinClick = { viewModel.onPinExpense(it) }
                )
            }

            // ── Place insights list ───────────────────────────────────────────
            if (state.placeInsights.isNotEmpty() &&
                state.overpassCandidates.isEmpty() &&
                state.selectedMarker == null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.45f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.map_top_spending_places),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    items(state.placeInsights.take(10)) { insight ->
                        PlaceInsightCard(insight)
                    }
                }
            }
        }
    }
}

// ── osmdroid MapView composable ───────────────────────────────────────────────

@SuppressLint("ClickableViewAccessibility")
@Suppress("DEPRECATION") // PreferenceManager.getDefaultSharedPreferences is fine for osmdroid config
@Composable
private fun OsmMapView(
    markers: List<MapExpenseMarker>,
    heatmapPoints: List<com.yourname.expensetracker.domain.location.HeatmapPoint>,
    deviceLat: Double?,
    deviceLon: Double?,
    centreOnDeviceRequest: Boolean,  // F3: one-shot flag to animate to device location
    onCentreHandled: () -> Unit,     // F3: call this after the animation is triggered
    onMarkerClick: (MapExpenseMarker) -> Unit
) {
    val context = LocalContext.current

    // Default centre: Athens, Greece
    val defaultLat = 37.9838
    val defaultLon = 23.7275

    // Keep a stable reference to the MapView for lifecycle management.
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // Bug #6 fix: track the last device location we centred on so we only
    // re-centre when a *new* location arrives (not on every recomposition).
    val lastCentredLoc = remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // F6: Track the last overlay key to avoid clearing overlays when nothing changed.
    val lastRenderKey = remember { mutableStateOf("") }

    AndroidView(
        factory = { ctx ->
            // F4: Load osmdroid config synchronously before MapView construction so
            //     tile cache paths are available when the MapView is first created.
            Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
            Configuration.getInstance().userAgentValue =
                com.yourname.expensetracker.domain.config.AppConfig.Location.NOMINATIM_USER_AGENT

            MapView(ctx).also { mv ->
                mv.setTileSource(TileSourceFactory.MAPNIK)
                mv.setMultiTouchControls(true)
                mv.controller.setZoom(13.0)
                mv.controller.setCenter(GeoPoint(defaultLat, defaultLon))
                mv.setBuiltInZoomControls(false)
                mv.onResume()  // F5: start tile-download threads immediately

                // B9: Tell parent containers (Column weight, Scaffold, etc.) to stop
                // intercepting touch events when the user is interacting with the map.
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
                    false
                }

                mapViewRef.value = mv
            }
        },
        update = { mapView ->
            // F3: Handle one-shot re-centre request from the MyLocation FAB.
            if (centreOnDeviceRequest && deviceLat != null && deviceLon != null) {
                mapView.controller.animateTo(GeoPoint(deviceLat, deviceLon))
                onCentreHandled()
            }

            // ── Bug #6 fix: re-centre when device location first arrives ──────
            if (deviceLat != null && deviceLon != null) {
                val newLoc = Pair(deviceLat, deviceLon)
                if (lastCentredLoc.value != newLoc) {
                    mapView.controller.animateTo(GeoPoint(deviceLat, deviceLon))
                    lastCentredLoc.value = newLoc
                }
            }

            // F6/L5: Build a compact, stable hash-based render key; skip overlay
            // rebuild if data hasn't changed. Marker signature includes identity
            // + metadata (expenseId, merchant, amount, date, source, placeId,
            // and coordinates) so metadata-only changes still trigger refresh.
            val markerSignature = markers.fold(1) { acc, marker ->
                31 * acc + markerRenderSignature(marker)
            }
            val heatmapSignature = heatmapPoints.fold(1) { acc, point ->
                31 * acc + heatmapRenderSignature(point)
            }
            val newKey = "m:${markers.size}:$markerSignature|h:${heatmapPoints.size}:$heatmapSignature"
            if (newKey == lastRenderKey.value) return@AndroidView
            lastRenderKey.value = newKey

            mapView.overlays.clear()

            // ── Heatmap circles ───────────────────────────────────────────────
            for (point in heatmapPoints) {
                val polygon = Polygon(mapView).apply {
                    val radiusMetres = 80.0 + point.weight * 320.0
                    points = Polygon.pointsAsCircle(
                        GeoPoint(point.latitude, point.longitude),
                        radiusMetres
                    )
                    val alpha = (40 + point.weight * 120).toInt()
                    val red = 0xE0
                    val green = max(0, (0x80 - (point.weight * 0x80)).toInt())
                    fillPaint.color = android.graphics.Color.argb(alpha, red, green, 0)
                    outlinePaint.color = android.graphics.Color.argb(0, 0, 0, 0)
                    outlinePaint.strokeWidth = 0f
                }
                mapView.overlays.add(polygon)
            }

            // ── Expense markers ───────────────────────────────────────────────
            for (expMarker in markers) {
                val osmMarker = Marker(mapView).apply {
                    position = GeoPoint(expMarker.latitude, expMarker.longitude)
                    // B7: suppress default info-window bubble (white rectangle on tap)
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        onMarkerClick(expMarker)
                        true
                    }
                }
                mapView.overlays.add(osmMarker)
            }

            mapView.invalidate()
        },
        modifier = Modifier.fillMaxSize()
    )

    // Bug #4 fix: manage osmdroid MapView lifecycle so tile threads are
    // properly paused/resumed and released when the Composable leaves composition.
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

// ── Marker detail card ────────────────────────────────────────────────────────

@Composable
private fun MarkerDetailCard(
    marker: MapExpenseMarker,
    isResolvingLocation: Boolean,
    onReResolve: () -> Unit,
    onCorrectPin: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = marker.merchant, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = CurrencyFormatter.format(marker.amount),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    marker.locationSource?.let { src ->
                        Text(
                            text = stringResource(R.string.map_source_format, src),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_close_button)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReResolve,
                    enabled = !isResolvingLocation
                ) {
                    if (isResolvingLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.map_reresolve_button))
                }
                OutlinedButton(onClick = onCorrectPin) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.map_correct_pin_button))
                }
            }
        }
    }
}

// ── Stats bar ─────────────────────────────────────────────────────────────────

@Composable
private fun LocationStatsBar(located: Int, unlocated: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.map_stats_format, located, unlocated),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

// ── Unlocated expenses panel (Feature E) ─────────────────────────────────────

@Composable
private fun UnlocatedExpensesPanel(
    expenses: List<Expense>,
    onPinClick: (Expense) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AddLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val suffix = if (expenses.size == 1) stringResource(R.string.map_unlocated_suffix_single) else stringResource(R.string.map_unlocated_suffix_plural)
                    Text(
                        text = stringResource(R.string.map_unlocated_expenses_format, expenses.size, suffix),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.a11y_collapse) else stringResource(R.string.a11y_expand)
                )
            }

            if (expanded) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(expenses) { expense ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = expense.merchant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = expense.resolvedAddress ?: stringResource(R.string.map_no_address),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = CurrencyFormatter.format(expense.amount, expense.currency),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { onPinClick(expense) }) {
                                Icon(
                                    Icons.Default.AddLocation,
                                    contentDescription = stringResource(R.string.map_pin_expense_cd),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun markerRenderSignature(marker: MapExpenseMarker): Int {
    var result = marker.expenseId.hashCode()
    result = 31 * result + marker.latitude.hashCode()
    result = 31 * result + marker.longitude.hashCode()
    result = 31 * result + marker.merchant.hashCode()
    result = 31 * result + marker.amount.hashCode()
    result = 31 * result + marker.date.hashCode()
    result = 31 * result + (marker.locationSource?.hashCode() ?: 0)
    result = 31 * result + (marker.placeId?.hashCode() ?: 0)
    return result
}

private fun heatmapRenderSignature(point: com.yourname.expensetracker.domain.location.HeatmapPoint): Int {
    var result = point.latitude.hashCode()
    result = 31 * result + point.longitude.hashCode()
    result = 31 * result + point.weight.hashCode()
    result = 31 * result + point.totalSpend.hashCode()
    result = 31 * result + point.count
    return result
}

// ── Pin expense sheet (Feature E) ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinExpenseSheet(
    expense: Expense,
    onDismiss: () -> Unit,
    onSave: (Double, Double, String?, String?) -> Unit,
    geocodingService: com.yourname.expensetracker.domain.location.GeocodingService,
    deviceLat: Double? = null,
    deviceLon: Double? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.map_pin_expense_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${expense.merchant} · ${CurrencyFormatter.format(expense.amount, expense.currency)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            var selectedLat by remember { mutableStateOf<Double?>(null) }
            var selectedLon by remember { mutableStateOf<Double?>(null) }
            var selectedAddress by remember { mutableStateOf<String?>(null) }
            var selectedOsmId by remember { mutableStateOf<String?>(null) }

            LocationSearchPicker(
                currentLat = expense.latitude,
                currentLon = expense.longitude,
                currentAddress = expense.resolvedAddress,
                onResult = { lat, lon, address, osmId ->
                    selectedLat = lat
                    selectedLon = lon
                    selectedAddress = address
                    selectedOsmId = osmId
                },
                geocodingService = geocodingService,
                // Bias toward device location when available, else expense's own location
                biasLat = deviceLat ?: expense.latitude,
                biasLon = deviceLon ?: expense.longitude
            )

            Spacer(modifier = Modifier.height(16.dp))

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
                        if (selectedLat != null && selectedLon != null) {
                            onSave(selectedLat!!, selectedLon!!, selectedAddress, selectedOsmId)
                        }
                    },
                    enabled = selectedLat != null && selectedLon != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
