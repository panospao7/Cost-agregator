package com.yourname.expensetracker.ui.model

/**
 * # Route / Content / Component Split Pattern
 *
 * Universal Compose structure for all feature screens:
 *
 * ```
 * FeatureRoute.kt       // ViewModel/Hilt/state collection/events
 * FeatureContent.kt     // pure state + callbacks (testable without ViewModel)
 * FeatureCards.kt       // reusable card components
 * FeatureDialogs.kt     // dialogs/sheets
 * ```
 *
 * ## Rules:
 * 1. Route collects ViewModel state and handles one-off events (navigation, snackbar)
 * 2. Content is a pure @Composable with state params + callback lambdas (no ViewModel)
 * 3. Components are reusable UI pieces with no domain knowledge
 * 4. No composable calls domain/network services directly
 *
 * ## Example:
 * ```kotlin
 * // FeatureRoute.kt
 * @Composable
 * fun FeatureRoute(
 *     onNavigateBack: () -> Unit,
 *     viewModel: FeatureViewModel = hiltViewModel()
 * ) {
 *     val state by viewModel.uiState.collectAsStateWithLifecycle()
 *     val mutation by viewModel.mutation.collectAsState()
 *
 *     LaunchedEffect(mutation.isSuccess) {
 *         if (mutation.isSuccess) onNavigateBack()
 *     }
 *
 *     FeatureContent(
 *         state = state,
 *         mutation = mutation,
 *         onSave = viewModel::save,
 *         onDelete = viewModel::delete,
 *         onNavigateBack = onNavigateBack
 *     )
 * }
 *
 * // FeatureContent.kt
 * @Composable
 * fun FeatureContent(
 *     state: LoadableUiState<FeatureData>,
 *     mutation: MutationState,
 *     onSave: () -> Unit,
 *     onDelete: (Long) -> Unit,
 *     onNavigateBack: () -> Unit
 * ) {
 *     when (state) {
 *         is LoadableUiState.Loading -> LoadingSkeleton()
 *         is LoadableUiState.Data -> { /* render content */ }
 *         is LoadableUiState.Empty -> EmptyState(...)
 *         is LoadableUiState.Error -> ErrorState(...)
 *     }
 * }
 * ```
 *
 * ## Migration strategy:
 * - New screens: follow this pattern from the start
 * - Existing screens: extract incrementally during slice debugging
 * - Do NOT rewrite all screens at once
 */
object RouteContentPattern {
    // This object exists only to hold the documentation above.
    // It is not instantiated at runtime.
}
