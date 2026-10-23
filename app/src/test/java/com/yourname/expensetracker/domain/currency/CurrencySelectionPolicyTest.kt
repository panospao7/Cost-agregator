package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.domain.core.money.CurrencyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * manual-entry-currency A1: [CurrencySelectionPolicy] (INV-10, INV-11; AE-25, AE-27 selection parts).
 */
class CurrencySelectionPolicyTest {

    private fun code(value: String): CurrencyCode =
        requireNotNull(CurrencyCode.parse(value)) { "test currency code must parse" }

    private val eur = code("EUR")
    private val usd = code("USD")
    private val jpy = code("JPY")
    private val bgn = code("BGN")
    private val rub = code("RUB")
    private val hrk = code("HRK")

    private fun standard(home: CurrencyCode): List<CurrencyCode> =
        CurrencySelectionPolicy.selectable(home, CurrencyEntryMode.STANDARD)

    // ---- STANDARD mode ----

    @Test
    fun `EUR home options are the active catalog minus BGN and RUB, with HRK absent`() {
        val options = standard(eur)
        val expected = SupportedCurrency.activeCatalog.map { it.code }.toSet() - setOf("BGN", "RUB")
        assertEquals(expected, options.map { it.code }.toSet())
        assertFalse(bgn in options)
        assertFalse(rub in options)
        assertFalse(hrk in options)
        assertEquals("no duplicates", options.size, options.toSet().size)
    }

    @Test
    fun `AE-27 BGN RUB and HRK are not offered to a EUR-home user`() {
        for (omitted in listOf(bgn, rub, hrk)) {
            assertFalse("${omitted.code} must be omitted", omitted in standard(eur))
            assertFalse(CurrencySelectionPolicy.isSelectable(omitted, eur, CurrencyEntryMode.STANDARD))
        }
    }

    @Test
    fun `home is always present and first, even when inactive or without a rate source`() {
        for (home in listOf(eur, jpy, bgn, rub, hrk)) {
            val options = standard(home)
            assertTrue("home ${home.code} present", home in options)
            assertEquals("home ${home.code} first", home, options.first())
        }
    }

    @Test
    fun `AE-27 a BGN-home user can select BGN but not RUB or HRK`() {
        val options = standard(bgn)
        assertTrue(bgn in options)
        assertFalse(rub in options)
        assertFalse(hrk in options)
        assertTrue(eur in options)
    }

    @Test
    fun `options after the home currency are sorted by code`() {
        val rest = standard(jpy).drop(1)
        assertEquals(rest.sorted(), rest)
    }

    @Test
    fun `a non-catalog home is still present so that home entries can be saved`() {
        val bhd = code("BHD")
        val options = standard(bhd)
        assertEquals(bhd, options.first())
        assertTrue(eur in options)
    }

    @Test
    fun `selectable does not depend on SupportedCurrency isActive being changed`() {
        assertFalse(SupportedCurrency.HRK.isActive)
        assertTrue(SupportedCurrency.BGN.isActive)
        assertTrue(SupportedCurrency.RUB.isActive)
        assertEquals(setOf(bgn, rub), CurrencySelectionPolicy.NO_RATE_SOURCE)
    }

    // ---- INSTALLMENT mode ----

    @Test
    fun `INSTALLMENT options are EUR and USD only`() {
        for (home in listOf(eur, usd, jpy, bgn)) {
            val options = CurrencySelectionPolicy.selectable(home, CurrencyEntryMode.INSTALLMENT)
            assertEquals("home ${home.code}", setOf(eur, usd), options.toSet())
            assertEquals("no duplicates for ${home.code}", 2, options.size)
        }
    }

    @Test
    fun `INSTALLMENT does not add a home that is neither EUR nor USD`() {
        assertFalse(jpy in CurrencySelectionPolicy.selectable(jpy, CurrencyEntryMode.INSTALLMENT))
        assertFalse(bgn in CurrencySelectionPolicy.selectable(bgn, CurrencyEntryMode.INSTALLMENT))
    }

    @Test
    fun `INSTALLMENT with a USD home lists the home first`() {
        assertEquals(listOf(usd, eur), CurrencySelectionPolicy.selectable(usd, CurrencyEntryMode.INSTALLMENT))
    }

    @Test
    fun `AE-28 a JPY proposal in installment mode is Unset, not swapped`() {
        assertEquals(
            CurrencySelection.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE),
            CurrencySelectionPolicy.resolveProposal(jpy, eur, CurrencyEntryMode.INSTALLMENT)
        )
    }

    // ---- resolveProposal ----

    @Test
    fun `AE-25 an inactive proposal is Unset PROPOSAL_NOT_SELECTABLE and not replaced by home`() {
        assertEquals(
            CurrencySelection.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE),
            CurrencySelectionPolicy.resolveProposal(bgn, eur, CurrencyEntryMode.STANDARD)
        )
        assertEquals(
            CurrencySelection.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE),
            CurrencySelectionPolicy.resolveProposal(hrk, eur, CurrencyEntryMode.STANDARD)
        )
        assertEquals(
            CurrencySelection.Unset(CurrencyUnsetReason.PROPOSAL_NOT_SELECTABLE),
            CurrencySelectionPolicy.resolveProposal(code("ZZZ"), eur, CurrencyEntryMode.STANDARD)
        )
    }

    @Test
    fun `no proposal is Unset NONE_PROPOSED`() {
        assertEquals(
            CurrencySelection.Unset(CurrencyUnsetReason.NONE_PROPOSED),
            CurrencySelectionPolicy.resolveProposal(null, eur, CurrencyEntryMode.STANDARD)
        )
    }

    @Test
    fun `a selectable proposal is Selected and flags home correctly`() {
        assertEquals(
            CurrencySelection.Selected(usd, isHome = false),
            CurrencySelectionPolicy.resolveProposal(usd, eur, CurrencyEntryMode.STANDARD)
        )
        assertEquals(
            CurrencySelection.Selected(eur, isHome = true),
            CurrencySelectionPolicy.resolveProposal(eur, eur, CurrencyEntryMode.STANDARD)
        )
    }

    @Test
    fun `a proposal equal to an inactive home is Selected as home`() {
        assertEquals(
            CurrencySelection.Selected(bgn, isHome = true),
            CurrencySelectionPolicy.resolveProposal(bgn, bgn, CurrencyEntryMode.STANDARD)
        )
    }
}
