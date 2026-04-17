package com.yourname.expensetracker.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityDeepLinkTest {

    @Test
    fun `anomaly deep link uses supported activity host with expense id query`() {
        val notificationServiceSource = source(
            "app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt"
        )

        assertTrue(notificationServiceSource.contains("expensetracker://activity?expenseId="))
    }

    @Test
    fun `main activity threads analytics and map deep link payloads into typed destinations`() {
        val source = source("app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt")

        assertTrue(source.contains("NavigationDestination.Analytics("))
        assertTrue(source.contains("initialPeriod = data.getQueryParameter(\"period\")"))
        assertTrue(source.contains("NavigationDestination.SpendingMap("))
        assertTrue(source.contains("initialLocationQuery = data.getQueryParameter(\"location\")"))
        assertTrue(source.contains("mainViewModel.navigateToTransactions("))
        assertTrue(source.contains("TransactionFilter(dateRange = startOfDay to calendar.timeInMillis)"))
    }

    private fun source(relativePath: String): String {
        val file = File(relativePath)
        require(file.exists()) { "Missing source file: $relativePath" }
        return file.readText()
    }
}
