package com.yourname.expensetracker.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

/**
 * Base setup for ViewModel tests using Main dispatcher replacement.
 * Use StandardTestDispatcher for deterministic coroutine execution.
 *
 * Usage:
 * ```
 * class MyViewModelTest : ViewModelTestUtils() {
 *     private val testDispatcher = StandardTestDispatcher()
 *
 *     @Before
 *     override fun setup() {
 *         super.setup()
 *         // your setup
 *     }
 *
 *     @Test
 *     fun myTest() = runTest(testDispatcher) {
 *         // test code
 *         testDispatcher.scheduler.advanceUntilIdle()
 *     }
 * }
 * ```
 */
abstract class ViewModelTestUtils {

    private val testScheduler = TestCoroutineScheduler()
    protected val testDispatcher = StandardTestDispatcher(testScheduler)

    @Before
    open fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    open fun tearDown() {
        Dispatchers.resetMain()
    }
}
