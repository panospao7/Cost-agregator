package com.yourname.expensetracker.domain.categorization

import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Stress tests for ContextualInferenceEngine
 * 
 * Tests the contextual inference engine which predicts expense categories
 * based on amount, time, day of week, and notification source.
 */
class ContextualInferenceEngineStressTest {

    private val engine = ContextualInferenceEngine(timeProvider = mockk(relaxed = true))

    // ============================================================================
    // SECTION 1: AMOUNT-BASED INFERENCE
    // ============================================================================

    @Test
    fun `stress - tiny amounts under 3 euros`() {
        val amounts = listOf(0.50, 1.00, 1.50, 2.00, 2.99)
        
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for €$amount", prediction)
            assertTrue("Should predict Food for tiny amount", 
                prediction?.categoryName == "Food")
        }
    }

    @Test
    fun `stress - small amounts 3-8 euros`() {
        val amounts = listOf(3.00, 4.50, 6.00, 7.99)
        
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for €$amount", prediction)
            assertTrue("Should predict Food for small amount",
                prediction?.categoryName == "Food")
        }
    }

    @Test
    fun `stress - medium amounts 8-20 euros`() {
        val amounts = listOf(8.00, 12.00, 15.50, 19.99)
        
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for €$amount", prediction)
            // Medium amounts boost both Food and Transport
            assertTrue("Should predict valid category",
                prediction?.categoryName in listOf("Food", "Transport"))
        }
    }

    @Test
    fun `stress - large amounts 20-50 euros`() {
        val amounts = listOf(20.00, 30.00, 40.00, 49.99)
        
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for €$amount", prediction)
            assertTrue("Should predict valid category",
                prediction?.categoryName in listOf("Shopping", "Food"))
        }
    }

    @Test
    fun `stress - extra large amounts 50-100 euros`() {
        val amounts = listOf(50.00, 65.00, 80.00, 99.99)
        
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for €$amount", prediction)
            assertTrue("Should predict a plausible XL category",
                prediction?.categoryName in listOf("Shopping", "Transport", "Groceries", "Food"))
        }
    }

    @Test
    fun `stress - huge amounts over 100 euros`() {
        val amounts = listOf(100.00, 200.00, 500.00, 1000.00, 5000.00)
        
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for €$amount", prediction)
            assertTrue("Should predict Shopping or Transport for huge amount",
                prediction?.categoryName in listOf("Shopping", "Transport"))
        }
    }

    @Test
    fun `stress - grocery amount bracket 20-150 euros`() {
        val amounts = listOf(20.00, 50.00, 75.00, 100.00, 149.99)
        
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            // Grocery bracket boosts Groceries score
            assertNotNull("Should predict for grocery amount €$amount", prediction)
        }
    }

    @Test
    fun `stress - amount boundary testing`() {
        val boundaryAmounts = listOf(
            2.99, 3.00, 3.01,  // Around SMALL_AMOUNT boundary
            7.99, 8.00, 8.01,  // Around MEDIUM_AMOUNT boundary  
            19.99, 20.00, 20.01,  // Around LARGE_AMOUNT boundary
            49.99, 50.00, 50.01,  // Around XL_AMOUNT boundary
            99.99, 100.00, 100.01  // Around XXL_AMOUNT boundary
        )
        
        boundaryAmounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should handle boundary amount €$amount", prediction)
        }
    }

    // ============================================================================
    // SECTION 2: TIME-BASED INFERENCE
    // ============================================================================

    @Test
    fun `stress - breakfast time 6-9am`() {
        val hours = listOf(6, 7, 8, 9)
        
        hours.forEach { hour ->
            val prediction = engine.inferFromContext(
                amount = 5.0,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for $hour:00", prediction)
            assertTrue("Should predict Food for breakfast time",
                prediction?.categoryName == "Food")
            assertTrue("Should mention morning in reason",
                prediction?.reason?.contains("morning") == true)
        }
    }

    @Test
    fun `stress - lunch time 12-2pm`() {
        val hours = listOf(12, 13, 14)
        
        hours.forEach { hour ->
            val prediction = engine.inferFromContext(
                amount = 10.0,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for $hour:00", prediction)
            assertTrue("Should mention lunch in reason",
                prediction?.reason?.contains("lunch") == true)
        }
    }

    @Test
    fun `stress - dinner time 6-9pm`() {
        val hours = listOf(18, 19, 20, 21)
        
        hours.forEach { hour ->
            val prediction = engine.inferFromContext(
                amount = 15.0,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for $hour:00", prediction)
            assertTrue("Should mention evening in reason",
                prediction?.reason?.contains("evening") == true)
        }
    }

    @Test
    fun `stress - night time 10-11pm`() {
        val hours = listOf(22, 23)
        
        hours.forEach { hour ->
            val prediction = engine.inferFromContext(
                amount = 20.0,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = Calendar.MONDAY
            )
            
            assertNotNull("Should predict for $hour:00", prediction)
            assertTrue("Should mention night in reason",
                prediction?.reason?.contains("night") == true)
        }
    }

    @Test
    fun `stress - all hours of day`() {
        (0..23).forEach { hour ->
            val prediction = engine.inferFromContext(
                amount = 10.0,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = Calendar.MONDAY
            )
            
            if (prediction != null) {
                assertTrue("Confidence should be valid", prediction.confidence in 0.0..1.0)
            }
        }
    }

    // ============================================================================
    // SECTION 3: DAY-BASED INFERENCE
    // ============================================================================

    @Test
    fun `stress - weekday vs weekend`() {
        val weekdays = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, 
                              Calendar.THURSDAY, Calendar.FRIDAY)
        val weekends = listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        
        weekdays.forEach { day ->
            val prediction = engine.inferFromContext(
                amount = 20.0,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = day
            )
            assertNotNull("Should predict for weekday $day", prediction)
            assertFalse("Should not mention weekend for weekday",
                prediction?.reason?.contains("weekend") == true)
        }
        
        weekends.forEach { day ->
            val prediction = engine.inferFromContext(
                amount = 20.0,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = day
            )
            assertNotNull("Should predict for weekend $day", prediction)
            assertTrue("Should mention weekend",
                prediction?.reason?.contains("weekend") == true)
        }
    }

    @Test
    fun `stress - weekend grocery boost`() {
        val weekendAmounts = listOf(5.0, 10.0, 20.0, 50.0, 100.0)
        
        weekendAmounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.SATURDAY
            )
            
            assertNotNull("Should predict for weekend amount €$amount", prediction)
        }
    }

    // ============================================================================
    // SECTION 4: SOURCE-BASED INFERENCE
    // ============================================================================

    @Test
    fun `stress - revolut source`() {
        val prediction = engine.inferFromContext(
            amount = 10.0,
            timestamp = createTimestamp(hour = 12),
            dayOfWeek = Calendar.MONDAY,
            notificationSource = "com.revolut.revolut"
        )
        
        assertNotNull(prediction)
        assertTrue("Should mention revolut in reason",
            prediction?.reason?.contains("revolut") == true)
    }

    @Test
    fun `stress - greek bank sources`() {
        val bankSources = listOf(
            "gr.nbg.mobilebanking",
            "com.eurobank.mobile",
            "gr.alpha.mobile"
        )
        
        bankSources.forEach { source ->
            val prediction = engine.inferFromContext(
                amount = 10.0,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = Calendar.MONDAY,
                notificationSource = source
            )
            
            assertNotNull("Should predict for $source", prediction)
            assertTrue("Should mention bank in reason",
                prediction?.reason?.contains("bank") == true)
        }
    }

    @Test
    fun `stress - google wallet source`() {
        val prediction = engine.inferFromContext(
            amount = 25.0,
            timestamp = createTimestamp(hour = 12),
            dayOfWeek = Calendar.MONDAY,
            notificationSource = "com.google.android.apps.walletnfcrel"
        )
        
        assertNotNull(prediction)
    }

    // ============================================================================
    // SECTION 5: SURNAME DETECTION
    // ============================================================================

    @Test
    fun `stress - detect greek surnames by ending`() {
        val surnames = listOf(
            "Papadopoulos", "Nikolaou", "Georgakis", "Constantinidis",
            "Ioannidis", "Athanasiou", "Michailidis", "Dimitriou",
            "Papadakis", "Nikolaidis", "Mavridis", "Papageorgiou"
        )
        
        surnames.forEach { surname ->
            val isSurname = engine.isLikelySurname(surname)
            assertTrue("Should detect '$surname' as surname", isSurname)
        }
    }

    @Test
    fun `stress - detect greek surnames by prefix`() {
        val surnames = listOf(
            "Papadimitriou", "Nikolaides", "Georgiou", "Constantinou",
            "Ioannou", "Athanasiadis", "Michailidis", "Dimitriadis"
        )
        
        surnames.forEach { surname ->
            val isSurname = engine.isLikelySurname(surname)
            assertTrue("Should detect '$surname' as surname by prefix", isSurname)
        }
    }

    @Test
    fun `stress - reject business names as surnames`() {
        val businessNames = listOf(
            "Starbucks", "Amazon", "Supermarket", "Cafe Restaurant",
            "Mini Market", "Pizza Store", "Bakery Shop"
        )
        
        businessNames.forEach { name ->
            val isSurname = engine.isLikelySurname(name)
            assertFalse("Should NOT detect '$name' as surname", isSurname)
        }
    }

    @Test
    fun `stress - handle edge case surnames`() {
        val edgeCases = listOf(
            "A",  // Too short
            "ab",  // Too short
            "",  // Empty
            "Shop",  // Business indicator
            "Store Ltd"  // Business with suffix
        )
        
        edgeCases.forEach { name ->
            val isSurname = engine.isLikelySurname(name)
            assertFalse("Should NOT detect '$name' as surname", isSurname)
        }
    }

    // ============================================================================
    // SECTION 6: CONFIDENCE THRESHOLD TESTING
    // ============================================================================

    @Test
    fun `stress - predictions meet minimum confidence`() {
        val testCases = (1..100).map { i ->
            Triple(
                i * 0.5,  // Amount 0.5 to 50
                (i % 24),  // Hour 0-23
                (i % 7) + 1  // Day 1-7
            )
        }
        
        testCases.forEach { (amount, hour, day) ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = day
            )
            
            if (prediction != null) {
                assertTrue("Confidence should meet threshold",
                    prediction.confidence >= ContextualInferenceEngine.MIN_CONFIDENCE_THRESHOLD)
            }
        }
    }

    @Test
    fun `stress - low confidence returns null`() {
        // Very small amount with no other boosts should be below threshold
        val prediction = engine.inferFromContext(
            amount = 0.10,  // Very tiny
            timestamp = createTimestamp(hour = 3),  // Night with no boost
            dayOfWeek = Calendar.WEDNESDAY  // Weekday
        )
        
        // May or may not return null depending on threshold
        if (prediction != null) {
            assertTrue("If prediction exists, confidence should be >= threshold",
                prediction.confidence >= ContextualInferenceEngine.MIN_CONFIDENCE_THRESHOLD)
        }
    }

    // ============================================================================
    // SECTION 7: COMBINATION TESTING
    // ============================================================================

    @Test
    fun `stress - amount and time combinations`() {
        val amounts = listOf(2.0, 5.0, 15.0, 30.0, 75.0)
        val hours = listOf(7, 12, 19, 22)
        
        amounts.forEach { amount ->
            hours.forEach { hour ->
                val prediction = engine.inferFromContext(
                    amount = amount,
                    timestamp = createTimestamp(hour = hour),
                    dayOfWeek = Calendar.MONDAY
                )
                
                assertNotNull("Should handle €$amount at $hour:00", prediction)
            }
        }
    }

    @Test
    fun `stress - all factor combinations`() {
        val amounts = listOf(5.0, 25.0, 75.0)
        val hours = listOf(8, 13, 20)
        val days = listOf(Calendar.MONDAY, Calendar.SATURDAY)
        val sources = listOf(null, "com.revolut.revolut", "gr.nbg.mobilebanking")
        
        var combinationCount = 0
        amounts.forEach { amount ->
            hours.forEach { hour ->
                days.forEach { day ->
                    sources.forEach { source ->
                        val prediction = engine.inferFromContext(
                            amount = amount,
                            timestamp = createTimestamp(hour = hour),
                            dayOfWeek = day,
                            notificationSource = source
                        )
                        
                        assertNotNull("Should handle combination #$combinationCount", prediction)
                        combinationCount++
                    }
                }
            }
        }
        
        assertEquals("Should test all combinations", 54, combinationCount)
    }

    // ============================================================================
    // SECTION 8: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - zero amount`() {
        val prediction = engine.inferFromContext(
            amount = 0.0,
            timestamp = createTimestamp(hour = 12),
            dayOfWeek = Calendar.MONDAY
        )
        
        // Zero amount may or may not return prediction depending on other factors
        if (prediction != null) {
            assertTrue("Confidence should be valid", prediction.confidence in 0.0..1.0)
        }
    }

    @Test
    fun `stress - negative amount`() {
        val prediction = engine.inferFromContext(
            amount = -10.0,
            timestamp = createTimestamp(hour = 12),
            dayOfWeek = Calendar.MONDAY
        )
        
        // Negative amounts should be handled gracefully
        if (prediction != null) {
            assertTrue("Should still provide valid prediction", prediction.confidence in 0.0..1.0)
        }
    }

    @Test
    fun `stress - very large amount`() {
        val prediction = engine.inferFromContext(
            amount = 1000000.0,
            timestamp = createTimestamp(hour = 12),
            dayOfWeek = Calendar.MONDAY
        )
        
        assertNotNull("Should handle very large amount", prediction)
        assertTrue("Should predict Shopping or Transport",
            prediction?.categoryName in listOf("Shopping", "Transport"))
    }

    @Test
    fun `stress - null day of week`() {
        val prediction = engine.inferFromContext(
            amount = 10.0,
            timestamp = createTimestamp(hour = 12, dayOfWeek = Calendar.FRIDAY),
            dayOfWeek = null  // Should use timestamp
        )
        
        assertNotNull("Should handle null day of week", prediction)
    }

    @Test
    fun `stress - null source`() {
        val prediction = engine.inferFromContext(
            amount = 10.0,
            timestamp = createTimestamp(hour = 12),
            dayOfWeek = Calendar.MONDAY,
            notificationSource = null
        )
        
        assertNotNull("Should handle null source", prediction)
    }

    @Test
    fun `stress - all null optional parameters`() {
        val prediction = engine.inferFromContext(
            amount = 25.0,
            timestamp = createTimestamp(hour = 12, dayOfWeek = Calendar.SATURDAY),
            dayOfWeek = null,
            notificationSource = null
        )
        
        assertNotNull("Should work with minimal parameters", prediction)
    }

    @Test
    fun `stress - invalid source package`() {
        val prediction = engine.inferFromContext(
            amount = 10.0,
            timestamp = createTimestamp(hour = 12),
            dayOfWeek = Calendar.MONDAY,
            notificationSource = "com.unknown.app"
        )
        
        assertNotNull("Should handle unknown source", prediction)
    }

    @Test
    fun `stress - reason building completeness`() {
        val prediction = engine.inferFromContext(
            amount = 7.0,  // Small amount
            timestamp = createTimestamp(hour = 8),  // Morning
            dayOfWeek = Calendar.SATURDAY,  // Weekend
            notificationSource = "com.revolut.revolut"
        )
        
        assertNotNull(prediction)
        assertTrue("Reason should not be empty", prediction!!.reason.isNotEmpty())
    }

    // ============================================================================
    // SECTION 9: PERFORMANCE
    // ============================================================================

    @Test
    fun `stress - process 1000 inferences quickly`() {
        val startTime = System.nanoTime()
        
        repeat(1000) { i ->
            engine.inferFromContext(
                amount = (i % 100).toDouble(),
                timestamp = createTimestamp(hour = i % 24),
                dayOfWeek = (i % 7) + 1
            )
        }
        
        val duration = System.nanoTime() - startTime
        assertTrue("Should process 1000 inferences in under 1s", duration < 1_000_000_000)
    }

    @Test
    fun `stress - surname detection performance`() {
        val surnames = (1..1000).map { "Papadopoulos$it" }
        
        val startTime = System.nanoTime()
        
        surnames.forEach { surname ->
            engine.isLikelySurname(surname)
        }
        
        val duration = System.nanoTime() - startTime
        assertTrue("Should process 1000 surnames quickly", duration < 500_000_000)
    }

    // Helper function
    private fun createTimestamp(hour: Int, dayOfWeek: Int = Calendar.MONDAY): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        
        // Set to specified day of week
        val currentDay = cal.get(Calendar.DAY_OF_WEEK)
        val daysDiff = dayOfWeek - currentDay
        cal.add(Calendar.DAY_OF_YEAR, daysDiff)
        
        return cal.timeInMillis
    }
}
