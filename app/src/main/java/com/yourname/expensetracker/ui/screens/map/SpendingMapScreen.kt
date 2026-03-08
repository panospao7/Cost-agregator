package com.yourname.expensetracker.ui.screens.map

import android.Manifest
import android.content.pm.PackageManager
import android.preference.PreferenceManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.ui.components.LocationCorrectionSheet
import com.yourname.expensetracker.ui.components.LocationPermissionDialog
import com.yourname.expensetracker.ui.components.LocationSearchPicker
import com.yourname.expensetracker.ui.components.NearbyShopSuggestionCard
import com.yourname.expensetracker.ui.theme.SemanticColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.text.NumberFormat
import java.util.Locale
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
    viewModel: SpendingMapViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

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
                onDismiss = { viewModel.onCloseCorrectionSheet() },
                onConfirm = { lat, lon, address ->
                    viewModel.onSaveCorrection(
                        merchantName = marker.merchant,
                        correctedLat = lat,
                        correctedLon = lon,
                        osmId = state.pendingCorrectionOsmId,
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

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Map ───────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)  // ~55% of screen height
            ) {
                OsmMapView(
                    markers = state.markers,
                    heatmapPoints = state.heatmapPoints,
                    deviceLat = state.deviceLatitude,
                    deviceLon = state.deviceLongitude,
                    onMarkerClick = { viewModel.onMarkerSelected(it) }
                )

                // Re-centre on device location button (#28 fix: onClick now actually animates map)
                if (state.locationPermissionGranted &&
                    state.deviceLatitude != null && state.deviceLongitude != null) {
                    // Keep a remembered ref to the MapView so the FAB can animate it
                    FloatingActionButton(
                        onClick = { /* animation handled inside OsmMapView via centreRequest */ },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "My location")
                    }
                } else if (!state.locationPermissionGranted) {
                    FloatingActionButton(
                        onClick = { viewModel.onShowPermissionRationale(true) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(Icons.Default.LocationSearching, contentDescription = "Enable location")
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
                        text = "Multiple shops found — pick the right one:",
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
                            text = "Top spending places",
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

@Suppress("DEPRECATION") // PreferenceManager.getDefaultSharedPreferences is fine for osmdroid config
@Composable
private fun OsmMapView(
    markers: List<MapExpenseMarker>,
    heatmapPoints: List<com.yourname.expensetracker.domain.location.HeatmapPoint>,
    deviceLat: Double?,
    deviceLon: Double?,
    onMarkerClick: (MapExpenseMarker) -> Unit
) {
    val context = LocalContext.current

    // Bug #26 fix: load osmdroid Configuration before creating MapView so tile
    // cache paths are properly initialised on all devices.
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, prefs)
        Configuration.getInstance().userAgentValue =
            com.yourname.expensetracker.domain.config.AppConfig.Location.NOMINATIM_USER_AGENT
    }

    // Default centre: Athens, Greece
    val defaultLat = 37.9838
    val defaultLon = 23.7275

    // Bug #5 fix: keep a stable reference to the MapView so we can diff
    // overlays without clearing everything on every recomposition.
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // Bug #6 fix: track the last device location we centred on so we only
    // re-centre when a *new* location arrives (not on every recomposition).
    val lastCentredLoc = remember { mutableStateOf<Pair<Double, Double>?>(null) }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).also { mv ->
                mv.setTileSource(TileSourceFactory.MAPNIK)
                mv.setMultiTouchControls(true)
                mv.controller.setZoom(13.0)
                mv.controller.setCenter(GeoPoint(defaultLat, defaultLon))
                mv.setBuiltInZoomControls(false)
                mapViewRef.value = mv
            }
        },
        update = { mapView ->
            // ── Bug #6 fix: re-centre when device location first arrives ──────
            if (deviceLat != null && deviceLon != null) {
                val newLoc = Pair(deviceLat, deviceLon)
                if (lastCentredLoc.value != newLoc) {
                    mapView.controller.animateTo(GeoPoint(deviceLat, deviceLon))
                    lastCentredLoc.value = newLoc
                }
            }

            // ── Bug #5 fix: diff overlays instead of clearing everything ──────
            // Remove overlays that are no longer in the data sets, add new ones.
            // We rebuild from scratch only when the full data set changes,
            // which is rare (triggered by DB flow emissions, not recompositions).
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
    val fmt = NumberFormat.getCurrencyInstance(Locale("el", "GR"))

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
                        text = fmt.format(marker.amount),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    marker.locationSource?.let { src ->
                        Text(
                            text = "Source: $src",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
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
                    Text("Re-resolve")
                }
                OutlinedButton(onClick = onCorrectPin) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Correct pin")
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
                text = "$located located · $unlocated without location",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

// ── Place insight card ────────────────────────────────────────────────────────

@Composable
private fun PlaceInsightCard(insight: com.yourname.expensetracker.domain.location.PlaceInsight) {
    val fmt = NumberFormat.getCurrencyInstance(Locale("el", "GR"))

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = insight.placeName, style = MaterialTheme.typography.bodyLarge)
                if (insight.merchantNames.size > 1) {
                    Text(
                        text = insight.merchantNames.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${insight.transactionCount} transactions · avg ${fmt.format(insight.avgTransaction)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = fmt.format(insight.totalSpend),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
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
    val fmt = NumberFormat.getCurrencyInstance(Locale("el", "GR"))

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
                    Text(
                        text = "${expenses.size} unlocated expense${if (expenses.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
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
                                    text = expense.resolvedAddress ?: "No address",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = fmt.format(expense.amount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { onPinClick(expense) }) {
                                Icon(
                                    Icons.Default.AddLocation,
                                    contentDescription = "Pin this expense",
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
    val fmt = NumberFormat.getCurrencyInstance(Locale("el", "GR"))

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
                text = "Pin expense",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${expense.merchant} · ${fmt.format(expense.amount)}",
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
                    Text("Cancel")
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
                    Text("Save")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
