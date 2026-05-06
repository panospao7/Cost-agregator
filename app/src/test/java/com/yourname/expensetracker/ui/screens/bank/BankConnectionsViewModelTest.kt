package com.yourname.expensetracker.ui.screens.bank

import com.yourname.expensetracker.util.ViewModelTestUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BankConnectionsViewModelTest : ViewModelTestUtils() {

    @Before
    override fun setup() {
        super.setup()
    }

    private fun createViewModel(): BankConnectionsViewModel {
        return BankConnectionsViewModel()
    }

    @Test
    fun `initial state has empty connections and not loading`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.connections.value.isEmpty())
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `refresh clears connections and reloads`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.connections.value.isEmpty())

        vm.refresh()
        advanceUntilIdle()

        assertTrue(vm.connections.value.isEmpty())
        assertFalse(vm.isLoading.value)
    }

    @Test
    fun `syncConnection does not crash`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.syncConnection(1L)
        advanceUntilIdle()

        assertTrue(vm.connections.value.isEmpty())
    }

    @Test
    fun `disconnect does not crash`() = runTest(testDispatcher) {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.disconnect(1L)
        advanceUntilIdle()

        assertTrue(vm.connections.value.isEmpty())
    }
}
