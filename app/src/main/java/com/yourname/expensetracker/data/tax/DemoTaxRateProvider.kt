package com.yourname.expensetracker.data.tax

import com.yourname.expensetracker.domain.tax.TaxRateProvider
import com.yourname.expensetracker.domain.tax.TaxRateConfidence
import com.yourname.expensetracker.domain.tax.TaxRateMetadata
import com.yourname.expensetracker.domain.tax.TaxRateResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T08-FIXED: Demo tax-rate provider with static EUR seed data.
 * Metadata declares source = "static_demo" and LOW confidence.
 */
@Singleton
class DemoTaxRateProvider @Inject constructor() : TaxRateProvider {
    
    override val metadata = TaxRateMetadata(
        source = "static_demo",
        confidence = TaxRateConfidence.LOW,
        isDemo = true
    )

    override suspend fun getRate(country: String, region: String?): TaxRateResult {
        return SEED_DATA[country.uppercase()]
            ?: TaxRateResult(standardVatRate = 20.0, currency = "EUR", country = country, region = region)
    }

    private companion object {
        private val SEED_DATA = mapOf(
            "GR" to TaxRateResult(24.0, listOf(13.0, 6.0), "EUR", "GR"),
            "DE" to TaxRateResult(19.0, listOf(7.0), "EUR", "DE"),
            "FR" to TaxRateResult(20.0, listOf(10.0, 5.5), "EUR", "FR"),
            "IT" to TaxRateResult(22.0, listOf(10.0, 5.0, 4.0), "EUR", "IT"),
            "ES" to TaxRateResult(21.0, listOf(10.0, 4.0), "EUR", "ES"),
            "GB" to TaxRateResult(20.0, listOf(5.0), "GBP", "GB"),
            "US" to TaxRateResult(0.0, emptyList(), "USD", "US"),
        )
    }
}
