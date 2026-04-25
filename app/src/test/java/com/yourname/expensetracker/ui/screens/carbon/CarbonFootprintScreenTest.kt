package com.yourname.expensetracker.ui.screens.carbon

import org.junit.Assert.assertEquals
import org.junit.Test

class CarbonFootprintScreenTest {

    @Test
    fun `resolveCarbonFootprintContentState returns loading when first load is in progress`() {
        val state = resolveCarbonFootprintContentState(
            hasReport = false,
            isLoading = true,
            hasError = false
        )

        assertEquals(CarbonFootprintContentState.FULL_SCREEN_LOADING, state)
    }

    @Test
    fun `resolveCarbonFootprintContentState returns full screen error when no report exists`() {
        val state = resolveCarbonFootprintContentState(
            hasReport = false,
            isLoading = false,
            hasError = true
        )

        assertEquals(CarbonFootprintContentState.FULL_SCREEN_ERROR, state)
    }

    @Test
    fun `resolveCarbonFootprintContentState keeps content visible when stale report exists with error`() {
        val state = resolveCarbonFootprintContentState(
            hasReport = true,
            isLoading = false,
            hasError = true
        )

        assertEquals(CarbonFootprintContentState.CONTENT, state)
    }
}
