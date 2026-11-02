package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.domain.forecasting.StressRecommendation
import com.yourname.expensetracker.domain.forecasting.StressRecommendationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * stress-forecast-actions AE-11 (INV-11): the card shows at most [STRESS_RECOMMENDATION_VISIBLE_CAP]
 * recommendations, taken in domain order.
 *
 * The defensive omission of an R2 row without `daysAhead` happens while rendering (a private
 * composable), not in [visibleStressRecommendations], so it is not unit-testable here.
 */
class StressRecommendationVisibleCapTest {

    private val r1 = StressRecommendation(StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS)
    private val r2 = StressRecommendation(StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING, daysAhead = 60)
    private val r3 = StressRecommendation(
        StressRecommendationKind.BUILD_EMERGENCY_BUFFER,
        bufferTarget = 500.0,
        bufferCurrency = "EUR"
    )
    private val r4 = StressRecommendation(StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES)

    @Test
    fun `INV-11 visible cap constant is two`() {
        assertEquals(2, STRESS_RECOMMENDATION_VISIBLE_CAP)
    }

    @Test
    fun `AE-11 four emitted items show the first two in domain order`() {
        val visible = visibleStressRecommendations(listOf(r1, r2, r3, r4))

        assertEquals(listOf(r1, r2), visible)
    }

    @Test
    fun `AE-11 domain order decides which two show not kind precedence`() {
        val visible = visibleStressRecommendations(listOf(r4, r3, r2, r1))

        assertEquals(listOf(r4, r3), visible)
    }

    @Test
    fun `INV-11 three items are capped at two`() {
        val visible = visibleStressRecommendations(listOf(r2, r3, r4))

        assertEquals(listOf(r2, r3), visible)
        assertEquals(STRESS_RECOMMENDATION_VISIBLE_CAP, visible.size)
    }

    @Test
    fun `INV-11 exactly two items are all shown in order`() {
        assertEquals(listOf(r1, r3), visibleStressRecommendations(listOf(r1, r3)))
    }

    @Test
    fun `INV-11 a single item is shown unchanged`() {
        assertEquals(listOf(r4), visibleStressRecommendations(listOf(r4)))
    }

    @Test
    fun `INV-11 an empty list stays empty`() {
        assertTrue(visibleStressRecommendations(emptyList()).isEmpty())
    }

    @Test
    fun `INV-11 the cap keeps the parameters of the shown items`() {
        val visible = visibleStressRecommendations(listOf(r2, r3, r1))

        assertEquals(60, visible[0].daysAhead)
        assertEquals(500.0, visible[1].bufferTarget)
        assertEquals("EUR", visible[1].bufferCurrency)
    }

    @Test
    fun `B1-11 stress_forecast_risk_percent_format exists and formats with percent placeholder`() {
        val stringsXml = listOf(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
            "../app/src/main/res/values/strings.xml"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("values/strings.xml not found", stringsXml)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsXml!!)
        val nodes = doc.getElementsByTagName("string")
        val rawValue = (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == "stress_forecast_risk_percent_format" }
            ?.textContent

        assertNotNull("stress_forecast_risk_percent_format must exist in strings.xml", rawValue)
        assertTrue(
            "stress_forecast_risk_percent_format must contain %1\$s placeholder, was: $rawValue",
            rawValue!!.contains("%1\$s")
        )
        assertEquals("25% risk", String.format(Locale.US, rawValue, "25"))
    }
}
