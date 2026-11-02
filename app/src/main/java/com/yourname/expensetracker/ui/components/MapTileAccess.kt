package com.yourname.expensetracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether the osmdroid base-map tile server may be contacted, plus the
 * resource-backed denied state to render when it may not.
 *
 * S19-2 (D7): fetching tiles discloses the viewed map area to the tile
 * provider, so it is gated by [PrivacyCapability.EXTERNAL_GEOCODING], the same
 * capability that guards every other outbound location lookup.
 */
@Immutable
data class MapTileAccessUiState(
    val tilesAllowed: Boolean,
    val blocked: PrivacyBlockedUiState?
) {
    companion object {
        /** Initial state before the gate has answered: tiles off, no card yet. */
        val Pending = MapTileAccessUiState(tilesAllowed = false, blocked = null)
    }
}

/**
 * Holder that exposes [tilesAllowed], initially `false`, driven by
 * `PrivacyGate.check(EXTERNAL_GEOCODING)`.
 *
 * Fail-closed: only [PrivacyDecision.Allowed] turns tiles on. Denied,
 * FailClosed, NotApplicable and any gate exception leave them off. The gate
 * exception text is never stored, rendered or logged; the denied state is the
 * resource-backed [PrivacyBlockedUiState] with a bounded reason code.
 */
@HiltViewModel
class MapTileAccessViewModel @Inject constructor(
    private val privacyGate: PrivacyGate
) : ViewModel() {

    private val _tilesAllowed = MutableStateFlow(false)
    val tilesAllowed: StateFlow<Boolean> = _tilesAllowed.asStateFlow()

    private val _blocked = MutableStateFlow<PrivacyBlockedUiState?>(null)
    val blocked: StateFlow<PrivacyBlockedUiState?> = _blocked.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** Re-evaluates the gate; the latest call wins. Call again after settings change. Tiles are switched off until the check answers, so a stale allow cannot request tiles. */
    fun refresh() {
        refreshJob?.cancel()
        _tilesAllowed.value = false
        refreshJob = viewModelScope.launch { evaluate() }
    }

    /** Suspend entry point for [refresh]; rethrows cancellation, fails closed otherwise. */
    suspend fun evaluate() {
        val allowed = try {
            privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING) is PrivacyDecision.Allowed
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (allowed) {
            _blocked.value = null
            _tilesAllowed.value = true
        } else {
            _blocked.value = DENIED
            _tilesAllowed.value = false
        }
    }

    private companion object {
        val DENIED = PrivacyBlockedUiState(
            capability = PrivacyCapability.EXTERNAL_GEOCODING,
            messageResId = R.string.privacy_blocked_external_geocoding,
            reasonCode = PrivacyGateReasonCodes.EXTERNAL_GEOCODING_DISABLED
        )
    }
}

/**
 * Collects the tile-access state and re-checks the gate on every resume, so a
 * change made in privacy settings takes effect when the user returns to the map.
 */
@Composable
fun rememberMapTileAccess(
    viewModel: MapTileAccessViewModel = hiltViewModel()
): MapTileAccessUiState {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val tilesAllowed by viewModel.tilesAllowed.collectAsStateWithLifecycle()
    val blocked by viewModel.blocked.collectAsStateWithLifecycle()
    return MapTileAccessUiState(tilesAllowed = tilesAllowed, blocked = blocked)
}
