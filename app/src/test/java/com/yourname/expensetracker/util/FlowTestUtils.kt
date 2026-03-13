package com.yourname.expensetracker.util

/**
 * Turbine Flow testing patterns and documentation.
 *
 * Add to test class:
 * ```
 * import app.cash.turbine.test
 * ```
 *
 * Usage for StateFlow (never completes):
 * ```
 * viewModel.state.test {
 *     assertEquals(true, awaitItem().isLoading)
 *     // trigger action, advance dispatcher
 *     val loaded = awaitItem()
 *     assertEquals(expected, loaded)
 *     cancelAndIgnoreRemainingEvents()
 * }
 * ```
 *
 * Usage for one-shot Flow:
 * ```
 * flow.test {
 *     assertEquals(expected, awaitItem())
 *     awaitComplete()
 * }
 * ```
 *
 * Run inside runTest { } for coroutine scope.
 */
object FlowTestUtils {
    const val DEFAULT_TIMEOUT_MS = 5000L
}
