package com.yourname.expensetracker.domain.tax

/**
 * HIGH FIX (HIGH-6): Tax rate configuration to replace hardcoded values.
 * 
 * Replaces hardcoded tax rates with configurable values.
 * Supports multiple tax systems (VAT, income tax brackets).
 * Can be extended to load from database or remote configuration.
 */
interface TaxConfiguration {
    fun getVatRate(): Double
    fun getTaxBrackets(): List<TaxBracket>
    fun getCountryCode(): String
    fun getCurrency(): String
}

/**
 * Tax bracket for progressive taxation.
 */
data class TaxBracket(
    val minIncome: Double,
    val maxIncome: Double?,
    val rate: Double,
    val name: String
)

/**
 * Greece tax configuration (default implementation).
 * Uses standard Greek VAT and income tax brackets.
 * 
 * TODO: Load from database table for per-country support
 * TODO: Add remote configuration for rate updates
 */
class GreeceTaxConfiguration : TaxConfiguration {
    
    override fun getVatRate(): Double = 0.24 // 24% VAT
    
    override fun getTaxBrackets(): List<TaxBracket> = listOf(
        TaxBracket(0.0, 10000.0, 0.09, "Low Income (≤€10k)"),
        TaxBracket(10000.0, 20000.0, 0.22, "Medium Income (€10k-20k)"),
        TaxBracket(20000.0, null, 0.32, "High Income (>€20k)")
    )
    
    override fun getCountryCode(): String = "GR"
    
    @Deprecated("Use currencySettingsRepository.homeCurrency() for user's tax currency. This is a Greece-specific default.")
    override fun getCurrency(): String = "EUR"
}

/**
 * Tax configuration for other countries can be added here.
 * Example: US, UK, Germany, etc.
 */
class UsTaxConfiguration : TaxConfiguration {
    // US has state-specific rates, so this is simplified
    override fun getVatRate(): Double = 0.0 // No federal VAT
    override fun getTaxBrackets(): List<TaxBracket> = listOf(
        TaxBracket(0.0, 11000.0, 0.10, "10% Bracket"),
        TaxBracket(11000.0, 44725.0, 0.12, "12% Bracket"),
        TaxBracket(44725.0, 95375.0, 0.22, "22% Bracket")
    )
    override fun getCountryCode(): String = "US"
    override fun getCurrency(): String = "USD"
}

/**
 * Factory to get appropriate tax configuration.
 * 
 * HIGH FIX: Centralizes tax rate access. Can be extended to:
 * - Load from user preferences
 * - Detect from location/GPS
 * - Load from database
 * - Fetch from remote config
 */
object TaxConfigurationFactory {
    
    private val configurations = mapOf(
        "GR" to GreeceTaxConfiguration(),
        "US" to UsTaxConfiguration()
        // Add more countries here
    )
    
    fun getConfiguration(countryCode: String = "GR"): TaxConfiguration {
        return configurations[countryCode] ?: GreeceTaxConfiguration() // Default to Greece
    }
    
    /**
     * Get configuration based on app locale or user preference.
     * TODO: Implement locale detection or user preference storage
     */
    fun getCurrentConfiguration(): TaxConfiguration {
        // For now, default to Greece
        // Future: Load from user settings
        return GreeceTaxConfiguration()
    }
}
