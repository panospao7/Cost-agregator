package com.yourname.expensetracker.data.repository

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for tax-filing related settings.
 *
 * Persists user preferences for tax country, filing currency, and fiscal-year
 * start month using [SharedPreferences]. These values are lightweight enough
 * that DataStore is not warranted — the settings change infrequently and are
 * read synchronously on cold start.
 */
@Singleton
class TaxSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Getters ──────────────────────────────────────────────────────────────

    /**
     * Returns the ISO 3166-1 alpha-2 country code for tax filing purposes.
     * Defaults to "GR" (Greece).
     */
    fun getTaxCountry(): String =
        prefs.getString(KEY_TAX_COUNTRY, DEFAULT_TAX_COUNTRY) ?: DEFAULT_TAX_COUNTRY

    /**
     * Returns the ISO 4217 currency code used for tax filing.
     * Defaults to "EUR".
     */
    fun getFilingCurrency(): String =
        prefs.getString(KEY_FILING_CURRENCY, DEFAULT_FILING_CURRENCY) ?: DEFAULT_FILING_CURRENCY

    /**
     * Returns the month (1-12) in which the fiscal year starts.
     * Defaults to 1 (January).
     */
    fun getFiscalYearStartMonth(): Int =
        prefs.getInt(KEY_FISCAL_YEAR_START, DEFAULT_FISCAL_YEAR_START)

    // ── Setters ──────────────────────────────────────────────────────────────

    /** Sets the tax filing country code. */
    fun setTaxCountry(country: String) {
        prefs.edit().putString(KEY_TAX_COUNTRY, country).apply()
    }

    /** Sets the tax filing currency code. */
    fun setFilingCurrency(currency: String) {
        prefs.edit().putString(KEY_FILING_CURRENCY, currency).apply()
    }

    companion object {
        private const val PREFS_NAME = "tax_settings"

        private const val KEY_TAX_COUNTRY = "tax_country"
        private const val KEY_FILING_CURRENCY = "tax_filing_currency"
        private const val KEY_FISCAL_YEAR_START = "tax_fiscal_year_start"

        private const val DEFAULT_TAX_COUNTRY = "GR"
        private const val DEFAULT_FILING_CURRENCY = "EUR"
        private const val DEFAULT_FISCAL_YEAR_START = 1
    }
}
