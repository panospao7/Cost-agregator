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

    /**
     * Returns the day of month (1-31) on which the fiscal year starts.
     * Defaults to 1.
     */
    fun getFiscalYearStartDay(): Int =
        prefs.getInt(KEY_FISCAL_YEAR_START_DAY, DEFAULT_FISCAL_YEAR_START_DAY)

    /**
     * Returns whether VAT tracking/reporting is enabled.
     * Defaults to false.
     */
    fun isVatEnabled(): Boolean =
        prefs.getBoolean(KEY_VAT_ENABLED, DEFAULT_VAT_ENABLED)

    /**
     * Returns the currency policy for business reports.
     * "HOME" = convert all amounts to home currency.
     * "FILING" = use filing currency as-is.
     * Defaults to "HOME".
     */
    fun getBusinessReportCurrencyPolicy(): String =
        prefs.getString(KEY_BUSINESS_REPORT_CURRENCY_POLICY, DEFAULT_BUSINESS_REPORT_CURRENCY_POLICY)
            ?: DEFAULT_BUSINESS_REPORT_CURRENCY_POLICY

    // ── Setters ──────────────────────────────────────────────────────────────

    /** Sets the tax filing country code. Must be ISO 3166-1 alpha-2 (2 letters). */
    fun setTaxCountry(country: String) {
        val normalized = country.trim().uppercase()
        require(normalized.matches(Regex("^[A-Z]{2}$"))) {
            "Tax country must be a 2-letter ISO code, got: $country"
        }
        prefs.edit().putString(KEY_TAX_COUNTRY, normalized).apply()
    }

    /** Sets the tax filing currency code. Must be ISO 4217 (3 letters). */
    fun setFilingCurrency(currency: String) {
        val normalized = currency.trim().uppercase()
        require(normalized.matches(Regex("^[A-Z]{3}$"))) {
            "Filing currency must be a 3-letter ISO code, got: $currency"
        }
        prefs.edit().putString(KEY_FILING_CURRENCY, normalized).apply()
    }

    /** Sets the fiscal year start month (1 = January, 12 = December). Fail-fast on invalid input. */
    fun setFiscalYearStartMonth(month: Int) {
        require(month in 1..12) { "Fiscal year start month must be 1-12, got: $month" }
        prefs.edit().putInt(KEY_FISCAL_YEAR_START, month).apply()
    }

    /** Sets the fiscal year start day (1-31). Fail-fast on invalid input. */
    fun setFiscalYearStartDay(day: Int) {
        require(day in 1..31) { "Fiscal year start day must be 1-31, got: $day" }
        prefs.edit().putInt(KEY_FISCAL_YEAR_START_DAY, day).apply()
    }

    /** Enables or disables VAT tracking/reporting. */
    fun setVatEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VAT_ENABLED, enabled).apply()
    }

    /**
     * Sets the currency policy for business reports.
     * @param policy Must be "HOME" or "FILING".
     */
    fun setBusinessReportCurrencyPolicy(policy: String) {
        require(policy == "HOME" || policy == "FILING") {
            "businessReportCurrencyPolicy must be HOME or FILING, got: $policy"
        }
        prefs.edit().putString(KEY_BUSINESS_REPORT_CURRENCY_POLICY, policy).apply()
    }

    companion object {
        private const val PREFS_NAME = "tax_settings"

        private const val KEY_TAX_COUNTRY = "tax_country"
        private const val KEY_FILING_CURRENCY = "tax_filing_currency"
        private const val KEY_FISCAL_YEAR_START = "tax_fiscal_year_start"
        private const val KEY_FISCAL_YEAR_START_DAY = "tax_fiscal_year_start_day"
        private const val KEY_VAT_ENABLED = "vat_enabled"
        private const val KEY_BUSINESS_REPORT_CURRENCY_POLICY = "business_report_currency_policy"

        private const val DEFAULT_TAX_COUNTRY = "GR"
        private const val DEFAULT_FILING_CURRENCY = "EUR"
        private const val DEFAULT_FISCAL_YEAR_START = 1
        private const val DEFAULT_FISCAL_YEAR_START_DAY = 1
        private const val DEFAULT_VAT_ENABLED = false
        private const val DEFAULT_BUSINESS_REPORT_CURRENCY_POLICY = "HOME"
    }
}
