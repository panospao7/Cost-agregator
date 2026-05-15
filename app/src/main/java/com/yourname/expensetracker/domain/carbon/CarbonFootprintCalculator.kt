package com.yourname.expensetracker.domain.carbon

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Singleton
class CarbonFootprintCalculator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val timeProvider: TimeProvider,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository
) {
    
    // CO2 emission factors (kg CO2 per euro spent) - simplified estimates
    // In production, these would be more sophisticated and region-specific
    private val categoryEmissionFactors = mapOf(
        // Food & Dining
        "RESTAURANT" to 0.35,     // Restaurant meals
        "FAST_FOOD" to 0.40,      // Fast food higher emissions
        "GROCERY" to 0.25,        // Groceries
        "MEAT" to 0.80,           // Meat products (high impact)
        "DAIRY" to 0.45,          // Dairy products
        "PRODUCE" to 0.15,        // Fruits and vegetables
        
        // Transport
        "FUEL" to 2.3,            // Gasoline (kg CO2 per liter, adjusted)
        "PUBLIC_TRANSPORT" to 0.15, // Bus, train
        "FLIGHT" to 0.50,         // Air travel (very high)
        "TAXI" to 0.35,           // Taxi/rideshare
        "CAR_RENTAL" to 0.30,     // Car rentals
        "PARKING" to 0.05,        // Minimal direct emissions
        
        // Shopping
        "CLOTHING" to 0.50,       // Fashion industry emissions
        "ELECTRONICS" to 0.80,    // Manufacturing emissions
        "FURNITURE" to 0.60,      // Wood, manufacturing
        "APPLIANCES" to 0.70,     // Manufacturing + electricity
        "SPORTING_GOODS" to 0.40, // Various materials
        "BEAUTY" to 0.45,         // Cosmetics, packaging
        "JEWELRY" to 1.2,         // Mining, manufacturing
        
        // Utilities
        "ELECTRICITY" to 0.40,    // Grid emissions factor
        "GAS" to 2.0,             // Natural gas
        "WATER" to 0.10,          // Treatment and pumping
        
        // Services
        "STREAMING" to 0.05,      // Data center energy
        "INTERNET" to 0.03,       // Infrastructure
        "PHONE" to 0.10,          // Device + network
        "INSURANCE" to 0.02,      // Administrative
        "SUBSCRIPTION" to 0.08,   // Average for services
        
        // Entertainment
        "ENTERTAINMENT" to 0.25,  // Events, venues
        "MOVIE" to 0.15,          // Theater energy
        "GAMES" to 0.08,          // Digital mostly
        
        // Healthcare
        "PHARMACY" to 0.30,       // Manufacturing, transport
        "HEALTHCARE" to 0.25,     // Services
        "GYM" to 0.12,            // Facility energy
        
        // Default
        "DEFAULT" to 0.25         // Average across all categories
    )
    
    // Alternative emission estimates based on merchant patterns
    private val merchantEmissionPatterns = mapOf(
        // Fuel stations
        "SHELL" to 2.3,
        "BP" to 2.3,
        "EKO" to 2.3,
        "REVOIL" to 2.3,
        "AVIN" to 2.3,
        
        // Airlines
        "AEGEAN" to 0.50,
        "OLYMPIC" to 0.50,
        "RYANAIR" to 0.45,
        "EASYJET" to 0.45,
        
        // Fast fashion
        "ZARA" to 0.55,
        "H&M" to 0.50,
        "BERSHKA" to 0.55,
        "STRADIVARIUS" to 0.55,
        
        // Electronics
        "PLAISIO" to 0.80,
        "PUBLIC" to 0.80,
        "GERMANOS" to 0.80,
        
        // Supermarkets
        "SKLAVENITIS" to 0.25,
        "AB" to 0.25,
        "LIDL" to 0.20,
        "MASOUTIS" to 0.25,
        "MY_MARKET" to 0.25,
        
        // Restaurants
        "EVEREST" to 0.35,
        "GOODY'S" to 0.40,
        "GREGORY" to 0.30,
        "STARBUCKS" to 0.35,
        "COFFEE_ISLAND" to 0.30
    )
    
    suspend fun calculateCarbonFootprint(
        startDate: Long? = null,
        endDate: Long? = null
    ): CarbonFootprintReport {
        val resolvedEndDate = endDate ?: timeProvider.now()
        val resolvedStartDate = startDate ?: (resolvedEndDate - (30L * 24 * 60 * 60 * 1000))

        // Keep carbon reporting on a one-shot uncapped snapshot so the
        // point-in-time report is never silently truncated by the old
        // LIMIT 2000 DAO path and never relies on live Flow observation.
        val expenses = expenseDao.getExpensesBetweenUncapped(resolvedStartDate, resolvedEndDate)
            .filter { it.transactionType.toDomain() == DomainTransactionType.PURCHASE }

        // S12-024: Normalize expenses to home currency before applying emission factors
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }.getOrNull()
        val normalizedAmountById = mutableMapOf<Long, Double>()
        if (homeCurrency != null) {
            try {
                val normResult = analyticsCurrencyNormalizer.normalizeExpenses(expenses, homeCurrency)
                for (ne in normResult.normalizedExpenses) {
                    normalizedAmountById[ne.snapshot.id] = ne.normalizedEffectiveAmount
                }
            } catch (_: Exception) { /* fall back to effectiveAmount per expense */ }
        }
        
        val categoryEmissions = mutableMapOf<String, Double>()
        val merchantEmissions = mutableMapOf<String, Double>()
        var totalEmissions = 0.0
        
        expenses.forEach { expense ->
            val factor = getEmissionFactor(expense)
            // S12-024: Use normalized amount if available, else fall back to effectiveAmount
            val normalizedAmount = normalizedAmountById[expense.id] ?: expense.effectiveAmount
            val co2 = normalizedAmount * factor
            
            totalEmissions += co2
            
            // Track by category (simplified - using merchant as proxy)
            val category = detectCategory(expense)
            categoryEmissions[category] = (categoryEmissions[category] ?: 0.0) + co2
            
            // Track by merchant
            val merchantKey = expense.merchant.take(20)
            merchantEmissions[merchantKey] = (merchantEmissions[merchantKey] ?: 0.0) + co2
        }
        
        // Calculate daily average
        val daysInPeriod = ((resolvedEndDate - resolvedStartDate) / (24 * 60 * 60 * 1000)).coerceAtLeast(1)
        val dailyAverage = totalEmissions / daysInPeriod
        
        // Compare to benchmarks
        val nationalAverage = 10.0 // kg CO2 per day (Greek average estimate)
        val globalAverage = 12.0 // kg CO2 per day
        val parisAgreementTarget = 4.0 // kg CO2 per day (2030 target)
        
        return CarbonFootprintReport(
            totalEmissionsKg = totalEmissions,
            dailyAverageKg = dailyAverage,
            periodDays = daysInPeriod.toInt(),
            categoryBreakdown = categoryEmissions.map { (category, emissions) ->
                CategoryEmission(
                    category = category,
                    emissionsKg = emissions,
                    percentage = (emissions / totalEmissions * 100).roundToInt(),
                    transactionCount = expenses.count { detectCategory(it) == category }
                )
            }.sortedByDescending { it.emissionsKg },
            merchantBreakdown = merchantEmissions.map { (merchant, emissions) ->
                MerchantEmission(
                    merchant = merchant,
                    emissionsKg = emissions,
                    percentage = (emissions / totalEmissions * 100).roundToInt()
                )
            }.sortedByDescending { it.emissionsKg }.take(5),
            comparisonToNationalAverage = ((dailyAverage / nationalAverage - 1) * 100).roundToInt(),
            comparisonToGlobalAverage = ((dailyAverage / globalAverage - 1) * 100).roundToInt(),
            parisAgreementGap = ((dailyAverage - parisAgreementTarget) / parisAgreementTarget * 100).roundToInt(),
            sustainabilityScore = calculateSustainabilityScore(dailyAverage, categoryEmissions),
            offsetCost = calculateOffsetCost(totalEmissions),
            recommendations = generateRecommendations(categoryEmissions, dailyAverage),
            alternatives = suggestAlternatives(expenses, categoryEmissions),
            monthlyTrend = calculateMonthlyTrend(expenses)
        )
    }
    
    private fun getEmissionFactor(expense: Expense): Double {
        // Try merchant-specific factor first
        val normalizedMerchant = expense.merchant.uppercase()
            .replace(Regex("[^A-Z]"), "")
        
        merchantEmissionPatterns.entries.forEach { (merchant, factor) ->
            if (normalizedMerchant.contains(merchant)) {
                return factor
            }
        }
        
        // Fall back to category-based
        val category = detectCategory(expense)
        return categoryEmissionFactors[category] ?: categoryEmissionFactors["DEFAULT"]!!
    }
    
    private fun detectCategory(expense: Expense): String {
        val merchant = expense.merchant.uppercase()
        
        return when {
            // Fuel
            merchant.containsAny("SHELL", "BP", "EKO", "REVOIL", "AVIN", "JETOIL", "ΕΚΟ", "ΒΕΝΖΙΝΗ", "FUEL", "GAS", "PETROL") -> "FUEL"
            
            // Flights
            merchant.containsAny("AEGEAN", "OLYMPIC", "RYANAIR", "EASYJET", "FLIGHT", "AIRLINE", "ΑΕΡΟΠΟΡΙΚΗ") -> "FLIGHT"
            
            // Public transport
            merchant.containsAny("KTEL", "OSE", "TRAM", "BUS", "METRO", "ΛΕΩΦΟΡΕΙΟ", "ΤΡΑΜ", "ΜΕΤΡΟ") -> "PUBLIC_TRANSPORT"
            
            // Taxi
            merchant.containsAny("TAXI", "UBER", "BEAT", "FREE NOW", "ΑΣΤΥΝΟΜΙΚΟ", "ΤΑΞΙ") -> "TAXI"
            
            // Groceries/Supermarkets
            merchant.containsAny("SKLAVENITIS", "AB", "LIDL", "MASOUTIS", "MY MARKET", "VEROPOULOS", "ΑΒ", "ΣΚΛΑΒΕΝΙΤΗΣ", "ΜΑΣΟΥΤΗΣ") -> "GROCERY"
            
            // Restaurants
            merchant.containsAny("EVEREST", "GOODY", "GREGORY", "STARBUCKS", "COFFEE", "RESTAURANT", "TAVERNA", "ΕΣΤΙΑΤΟΡΙΟ", "ΤΑΒΕΡΝΑ") -> "RESTAURANT"
            
            // Fast food
            merchant.containsAny("MC DONALD", "BURGER KING", "KFC", "GOODYS", "PIZZA", "ΣΟΥΒΛΑΚΙ", "ΠΙΤΣΑ") -> "FAST_FOOD"
            
            // Clothing/Fashion
            merchant.containsAny("ZARA", "H&M", "BERSHKA", "STRADIVARIUS", "MANGO", "PULL & BEAR", "MASSIMO", "CLOTHING", "FASHION") -> "CLOTHING"
            
            // Electronics
            merchant.containsAny("PLAISIO", "PUBLIC", "GERMANOS", "ΚΩΤΣΟΒΟΛΟΣ", "ELECTRONICS", "COMPUTER", "PHONE", "LAPTOP") -> "ELECTRONICS"
            
            // Utilities
            merchant.containsAny("DEI", "ΔΕΗ", "EYDAP", "ΕΥΔΑΠ", "EYATH", "ΕΥΑΘ", "UTILITY", "ELECTRIC", "WATER") -> "ELECTRICITY"
            
            // Streaming
            merchant.containsAny("NETFLIX", "SPOTIFY", "DISNEY", "HBO", "AMAZON PRIME", "STREAMING") -> "STREAMING"
            
            // Gym
            merchant.containsAny("GYM", "FITNESS", "CROSSFIT", "YOGA", "GYMNASIO", "ΓΥΜΝΑΣΤΗΡΙΟ") -> "GYM"
            
            // Pharmacy
            merchant.containsAny("PHARMACY", "FARMACY", "APOTEKE", "ΦΑΡΜΑΚΕΙΟ", "ΦΑΡΜΑΚΟ") -> "PHARMACY"
            
            else -> "DEFAULT"
        }
    }
    
    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }
    
    private fun calculateSustainabilityScore(
        dailyAverage: Double,
        categoryEmissions: Map<String, Double>
    ): Int {
        // Score from 0-100
        // Lower daily emissions = higher score
        var score = 100 - (dailyAverage * 5).toInt()
        
        // Bonus for sustainable categories
        val sustainableCategories = listOf("PUBLIC_TRANSPORT", "PRODUCE", "GROCERY", "STREAMING")
        val sustainableEmissions = categoryEmissions.filter { 
            sustainableCategories.any { cat -> it.key.contains(cat) }
        }.values.sum()
        
        val totalEmissions = categoryEmissions.values.sum()
        if (totalEmissions > 0) {
            val sustainableRatio = sustainableEmissions / totalEmissions
            score += (sustainableRatio * 20).toInt()
        }
        
        return score.coerceIn(0, 100)
    }
    
    private fun calculateOffsetCost(totalEmissions: Double): Double {
        // Average carbon offset cost: €15-30 per tonne CO2
        val costPerTonne = 22.0 // euros
        return (totalEmissions / 1000) * costPerTonne
    }
    
    private fun generateRecommendations(
        categoryEmissions: Map<String, Double>,
        dailyAverage: Double
    ): List<SustainabilityRecommendation> {
        val recommendations = mutableListOf<SustainabilityRecommendation>()
        
        // Sort categories by emissions
        val sortedCategories = categoryEmissions.entries.sortedByDescending { it.value }
        
        // Add recommendations for top emission sources
        sortedCategories.take(3).forEach { (category, emissions) ->
            when (category) {
                "FUEL" -> recommendations.add(
                    SustainabilityRecommendation(
                        category = "Transportation",
                        title = UiText.fromKey("domain_carbon_reduce_fuel"),
                        description = "Your fuel purchases account for significant emissions. Consider public transport, carpooling, or switching to an electric/hybrid vehicle.",
                        potentialImpact = "Up to 40% reduction",
                        difficulty = Difficulty.MEDIUM,
                        savings = emissions * 0.4
                    )
                )
                "FLIGHT" -> recommendations.add(
                    SustainabilityRecommendation(
                        category = "Travel",
                        title = UiText.fromKey("domain_carbon_fly_less"),
                        description = "Air travel has very high emissions. Consider train travel for shorter trips or purchase carbon offsets for necessary flights.",
                        potentialImpact = "50-100% reduction for avoided flights",
                        difficulty = Difficulty.HARD,
                        savings = emissions * 0.7
                    )
                )
                "CLOTHING" -> recommendations.add(
                    SustainabilityRecommendation(
                        category = "Shopping",
                        title = UiText.fromKey("domain_carbon_sustainable_fashion"),
                        description = "Fast fashion has high environmental impact. Try secondhand, sustainable brands, or a capsule wardrobe.",
                        potentialImpact = "30-50% reduction",
                        difficulty = Difficulty.EASY,
                        savings = emissions * 0.35
                    )
                )
                "RESTAURANT", "FAST_FOOD" -> recommendations.add(
                    SustainabilityRecommendation(
                        category = "Food",
                        title = UiText.fromKey("domain_carbon_eat_plants"),
                        description = "Restaurant meals and meat-heavy options have higher emissions. Try more plant-based options and cook at home.",
                        potentialImpact = "20-30% reduction",
                        difficulty = Difficulty.EASY,
                        savings = emissions * 0.25
                    )
                )
                "ELECTRONICS" -> recommendations.add(
                    SustainabilityRecommendation(
                        category = "Technology",
                        title = UiText.fromKey("domain_carbon_extend_device"),
                        description = "Electronics manufacturing is carbon-intensive. Keep devices longer, buy refurbished, and recycle properly.",
                        potentialImpact = "Up to 60% reduction",
                        difficulty = Difficulty.EASY,
                        savings = emissions * 0.4
                    )
                )
            }
        }
        
        // General recommendation if no specific high-impact categories
        if (recommendations.isEmpty()) {
            recommendations.add(
                SustainabilityRecommendation(
                    category = "General",
                        title = UiText.fromKey("domain_carbon_track_improve"),
                    description = "Your emissions are moderate. Continue tracking and look for small improvements in your daily spending habits.",
                    potentialImpact = "10-20% reduction",
                    difficulty = Difficulty.EASY,
                    savings = dailyAverage * 30 * 0.15
                )
            )
        }
        
        // Add offset recommendation
        recommendations.add(
            SustainabilityRecommendation(
                category = "Offset",
                        title = UiText.fromKey("domain_carbon_purchase_offsets"),
                description = "While reducing emissions is best, you can offset unavoidable emissions through verified carbon offset programs.",
                potentialImpact = "100% offset possible",
                difficulty = Difficulty.EASY,
                savings = 0.0,
                isOffset = true,
                offsetCost = calculateOffsetCost(categoryEmissions.values.sum())
            )
        )
        
        return recommendations
    }
    
    private fun suggestAlternatives(
        expenses: List<Expense>,
        categoryEmissions: Map<String, Double>
    ): List<SustainableAlternative> {
        val alternatives = mutableListOf<SustainableAlternative>()
        
        // Check for high-emission categories and suggest alternatives
        if (categoryEmissions["FUEL"] != null && categoryEmissions["FUEL"]!! > 10) {
            alternatives.add(
                SustainableAlternative(
                    currentBehavior = "Driving frequently",
                    alternative = "Use public transport or cycle",
                    co2Reduction = categoryEmissions["FUEL"]!! * 0.5,
                    costSavings = 200.0, // Estimated monthly savings
                    difficulty = Difficulty.MEDIUM
                )
            )
        }
        
        if (categoryEmissions["FLIGHT"] != null && categoryEmissions["FLIGHT"]!! > 20) {
            alternatives.add(
                SustainableAlternative(
                    currentBehavior = "Flying frequently",
                    alternative = "Take trains for shorter distances",
                    co2Reduction = categoryEmissions["FLIGHT"]!! * 0.8,
                    costSavings = 100.0,
                    difficulty = Difficulty.HARD
                )
            )
        }
        
        if (categoryEmissions["CLOTHING"] != null && categoryEmissions["CLOTHING"]!! > 5) {
            alternatives.add(
                SustainableAlternative(
                    currentBehavior = "Buying new clothes frequently",
                    alternative = "Buy secondhand or from sustainable brands",
                    co2Reduction = categoryEmissions["CLOTHING"]!! * 0.4,
                    costSavings = 50.0,
                    difficulty = Difficulty.EASY
                )
            )
        }
        
        return alternatives
    }
    
    private suspend fun calculateMonthlyTrend(expenses: List<Expense>): List<MonthlyEmission> {
        // S12-025: Do not fall back to "EUR" — use null/empty if home currency unavailable
        val homeCurrency = runCatching { currencySettingsRepository.homeCurrency().first() }.getOrNull()
            ?: return emptyList() // Cannot normalize without home currency
        val normalized = runCatching {
            analyticsCurrencyNormalizer.normalizeExpenses(expenses, homeCurrency)
        }.getOrNull()
        val normalizedAmountById = normalized?.includedExpenses?.associateBy { it.id }
            ?: emptyMap()

        val byMonth = expenses.groupBy { expense ->
            Instant.ofEpochMilli(expense.date)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .withDayOfMonth(1)
        }

        return byMonth.map { (month, monthExpenses) ->
            // SAFE: normalized via AnalyticsCurrencyNormalizer before summing
            val total = monthExpenses.sumOf { expense ->
                val amount = normalizedAmountById[expense.id]?.effectiveAmount
                    ?: expense.effectiveAmount
                amount * getEmissionFactor(expense)
            }
            MonthlyEmission(
                month = month.toString(),
                emissionsKg = total,
                transactionCount = monthExpenses.size
            )
        }.sortedBy { it.month }
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }
    
    // Data Classes
    data class CarbonFootprintReport(
        val totalEmissionsKg: Double,
        val dailyAverageKg: Double,
        val periodDays: Int,
        val categoryBreakdown: List<CategoryEmission>,
        val merchantBreakdown: List<MerchantEmission>,
        val comparisonToNationalAverage: Int, // Percentage difference
        val comparisonToGlobalAverage: Int,
        val parisAgreementGap: Int, // Percentage above target
        val sustainabilityScore: Int, // 0-100
        val offsetCost: Double, // Euros to offset
        val recommendations: List<SustainabilityRecommendation>,
        val alternatives: List<SustainableAlternative>,
        val monthlyTrend: List<MonthlyEmission>
    )
    
    data class CategoryEmission(
        val category: String,
        val emissionsKg: Double,
        val percentage: Int,
        val transactionCount: Int
    )
    
    data class MerchantEmission(
        val merchant: String,
        val emissionsKg: Double,
        val percentage: Int
    )
    
    data class SustainabilityRecommendation(
        val category: String,
        val title: UiText,
        val description: String,
        val potentialImpact: String,
        val difficulty: Difficulty,
        val savings: Double, // kg CO2 potential savings
        val isOffset: Boolean = false,
        val offsetCost: Double? = null
    )
    
    enum class Difficulty {
        EASY, MEDIUM, HARD
    }
    
    data class SustainableAlternative(
        val currentBehavior: String,
        val alternative: String,
        val co2Reduction: Double,
        val costSavings: Double,
        val difficulty: Difficulty
    )
    
    data class MonthlyEmission(
        val month: String,
        val emissionsKg: Double,
        val transactionCount: Int
    )
}
