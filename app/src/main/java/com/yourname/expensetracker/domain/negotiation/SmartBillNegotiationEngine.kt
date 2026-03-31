package com.yourname.expensetracker.domain.negotiation

import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.domain.model.RecurringPattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartBillNegotiationEngine @Inject constructor(
    private val recurringExpenseDao: ManualRecurringExpenseDao,
    private val priceHistoryDao: SubscriptionPriceHistoryDao
) {
    
    // Mock market rate database - in production, this would come from APIs
    private val marketRates = mapOf(
        // Internet Providers
        "COSMOTE" to MarketRate(
            serviceType = ServiceType.INTERNET,
            providerName = "Cosmote",
            averagePrice = 29.90,
            competitivePrice = 24.90,
            bestPrice = 19.90,
            unit = "month",
            competitors = listOf("Vodafone", "Nova", "Wind")
        ),
        "VODAFONE" to MarketRate(
            serviceType = ServiceType.INTERNET,
            providerName = "Vodafone",
            averagePrice = 27.90,
            competitivePrice = 22.90,
            bestPrice = 18.90,
            unit = "month",
            competitors = listOf("Cosmote", "Nova", "Wind")
        ),
        "NOVA" to MarketRate(
            serviceType = ServiceType.INTERNET,
            providerName = "Nova",
            averagePrice = 25.90,
            competitivePrice = 21.90,
            bestPrice = 17.90,
            unit = "month",
            competitors = listOf("Cosmote", "Vodafone", "Wind")
        ),
        
        // Mobile Providers
        "COSMOTE_MOBILE" to MarketRate(
            serviceType = ServiceType.MOBILE,
            providerName = "Cosmote Mobile",
            averagePrice = 19.90,
            competitivePrice = 15.90,
            bestPrice = 12.90,
            unit = "month",
            competitors = listOf("Vodafone CU", "What's Up", "Nova Mobile")
        ),
        "VODAFONE_CU" to MarketRate(
            serviceType = ServiceType.MOBILE,
            providerName = "Vodafone CU",
            averagePrice = 12.90,
            competitivePrice = 10.90,
            bestPrice = 8.90,
            unit = "month",
            competitors = listOf("What's Up", "Nova Mobile", "Cosmote")
        ),
        
        // Streaming
        "NETFLIX" to MarketRate(
            serviceType = ServiceType.STREAMING,
            providerName = "Netflix",
            averagePrice = 12.99,
            competitivePrice = 7.99,
            bestPrice = 7.99,
            unit = "month",
            competitors = listOf("Disney+", "Amazon Prime", "HBO Max")
        ),
        "SPOTIFY" to MarketRate(
            serviceType = ServiceType.STREAMING,
            providerName = "Spotify",
            averagePrice = 10.99,
            competitivePrice = 5.99,
            bestPrice = 0.0, // Free tier available
            unit = "month",
            competitors = listOf("Apple Music", "YouTube Music", "Deezer")
        ),
        
        // Insurance
        "ETHNIKI_INSURANCE" to MarketRate(
            serviceType = ServiceType.INSURANCE,
            providerName = "Ethniki Insurance",
            averagePrice = 45.00,
            competitivePrice = 38.00,
            bestPrice = 32.00,
            unit = "month",
            competitors = listOf("Euroins", "Interamerican", "Allianz")
        ),
        
        // Utilities
        "DEI" to MarketRate(
            serviceType = ServiceType.ENERGY,
            providerName = "DEI",
            averagePrice = 85.00,
            competitivePrice = 75.00,
            bestPrice = 65.00,
            unit = "month",
            competitors = listOf("Elpedison", "Heron", "Protergia")
        ),
        "EYDAP" to MarketRate(
            serviceType = ServiceType.WATER,
            providerName = "EYDAP",
            averagePrice = 18.00,
            competitivePrice = 16.00,
            bestPrice = 14.00,
            unit = "month",
            competitors = emptyList() // Usually monopoly
        )
    )
    
    suspend fun analyzeNegotiationOpportunities(): List<NegotiationOpportunity> {
        val subscriptions = recurringExpenseDao.getAll()
        val opportunities = mutableListOf<NegotiationOpportunity>()
        
        subscriptions.forEach { subscription ->
            val normalizedMerchant = normalizeMerchantName(subscription.merchant)
            val marketRate = findMarketRate(normalizedMerchant)
            
            if (marketRate != null) {
                val currentPrice = subscription.amount
                val priceHistory = priceHistoryDao.getAllPricesForSubscription(subscription.id)
                
                val opportunity = createNegotiationOpportunity(
                    subscription = subscription,
                    marketRate = marketRate,
                    currentPrice = currentPrice,
                    priceHistory = priceHistory
                )
                
                opportunities.add(opportunity)
            }
        }
        
        return opportunities.sortedByDescending { it.potentialMonthlySavings }
    }
    
    private fun findMarketRate(merchantName: String): MarketRate? {
        // Try exact match first
        marketRates[merchantName.uppercase()]?.let { return it }
        
        // Try partial match
        marketRates.entries.forEach { (key, rate) ->
            if (merchantName.uppercase().contains(key) || key.contains(merchantName.uppercase())) {
                return rate
            }
        }
        
        // Try by service type keywords
        return detectServiceType(merchantName)?.let { serviceType ->
            marketRates.values.find { it.serviceType == serviceType }
        }
    }
    
    private fun detectServiceType(merchantName: String): ServiceType? {
        val name = merchantName.uppercase()
        return when {
            name.containsAny("INTERNET", "FIBER", "VDSL", "BROADBAND", "COSMOTE", "VODAFONE", "WIND", "NOVA") -> ServiceType.INTERNET
            name.containsAny("MOBILE", "CELL", "PHONE", "COSMOTE", "VODAFONE", "WIND") -> ServiceType.MOBILE
            name.containsAny("NETFLIX", "SPOTIFY", "DISNEY", "HBO", "PRIME", "STREAMING") -> ServiceType.STREAMING
            name.containsAny("INSURANCE", "ΑΣΦΑΛΕΙΑ", "INSUR", "ASFI") -> ServiceType.INSURANCE
            name.containsAny("DEI", "EΝΕΡΓΕΙΑ", "ΕΛΠΕΔΙΣΩΝ", "ΗΡΩΝ", "ENERGY", "ELECTRICITY") -> ServiceType.ENERGY
            name.containsAny("GYM", "FITNESS", "SPORT", "ΓΥΜΝΑΣΤΗΡΙΟ") -> ServiceType.GYM
            name.containsAny("CLOUD", "STORAGE", "DROPBOX", "GOOGLE", "MICROSOFT", "365") -> ServiceType.SOFTWARE
            else -> null
        }
    }
    
    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }
    
    private fun createNegotiationOpportunity(
        subscription: ManualRecurringExpense,
        marketRate: MarketRate,
        currentPrice: Double,
        priceHistory: List<com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory>
    ): NegotiationOpportunity {
        val potentialSavings = currentPrice - marketRate.competitivePrice
        val savingsPercent = if (currentPrice > 0) (potentialSavings / currentPrice * 100) else 0.0
        
        val priceIncreases = analyzePriceIncreases(priceHistory)
        val customerValue = calculateCustomerValue(subscription, priceHistory.size)
        
        val negotiationPower = calculateNegotiationPower(
            currentPrice = currentPrice,
            marketRate = marketRate,
            customerValue = customerValue,
            priceIncreases = priceIncreases,
            hasCompetitors = marketRate.competitors.isNotEmpty()
        )
        
        return NegotiationOpportunity(
            subscriptionId = subscription.id,
            serviceName = subscription.merchant,
            serviceType = marketRate.serviceType,
            currentProvider = marketRate.providerName,
            currentPrice = currentPrice,
            billingCycle = subscription.frequency.name,
            marketAveragePrice = marketRate.averagePrice,
            competitivePrice = marketRate.competitivePrice,
            bestAvailablePrice = marketRate.bestPrice,
            potentialMonthlySavings = potentialSavings.coerceAtLeast(0.0),
            potentialYearlySavings = potentialSavings.coerceAtLeast(0.0) * 12,
            savingsPercent = savingsPercent,
            negotiationPower = negotiationPower,
            priceIncreaseCount = priceIncreases.size,
            lastPriceIncrease = priceHistory.maxByOrNull { it.recordedAt }?.recordedAt,
            alternativeProviders = marketRate.competitors,
            negotiationScript = generateNegotiationScript(
                subscription = subscription,
                marketRate = marketRate,
                negotiationPower = negotiationPower
            ),
            retentionOffers = generateRetentionOffers(marketRate, negotiationPower),
            successProbability = calculateSuccessProbability(negotiationPower, marketRate),
            recommendedAction = determineRecommendedAction(
                negotiationPower,
                potentialSavings,
                marketRate
            )
        )
    }
    
    private fun analyzePriceIncreases(
        priceHistory: List<com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory>
    ): List<PriceIncrease> {
        if (priceHistory.size < 2) return emptyList()
        
        val sorted = priceHistory.sortedBy { it.recordedAt }
        val increases = mutableListOf<PriceIncrease>()
        
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            
            if (curr.amount > prev.amount) {
                increases.add(
                    PriceIncrease(
                        date = curr.recordedAt,
                        oldPrice = prev.amount,
                        newPrice = curr.amount,
                        increaseAmount = curr.amount - prev.amount,
                        increasePercent = if (prev.amount > 0) 
                            ((curr.amount - prev.amount) / prev.amount * 100) else 0.0
                    )
                )
            }
        }
        
        return increases
    }
    
    private fun calculateCustomerValue(
        subscription: ManualRecurringExpense,
        priceHistoryCount: Int
    ): CustomerValue {
        val monthsActive = priceHistoryCount.coerceAtLeast(1)
        
        return when {
            monthsActive >= 24 -> CustomerValue.HIGH
            monthsActive >= 12 -> CustomerValue.MEDIUM
            else -> CustomerValue.NEW
        }
    }
    
    private fun calculateNegotiationPower(
        currentPrice: Double,
        marketRate: MarketRate,
        customerValue: CustomerValue,
        priceIncreases: List<PriceIncrease>,
        hasCompetitors: Boolean
    ): NegotiationPower {
        var score = 50 // Base score
        
        // Price gap from market rate
        val priceGap = (currentPrice - marketRate.competitivePrice) / marketRate.competitivePrice
        score += (priceGap * 30).toInt()
        
        // Customer loyalty
        score += when (customerValue) {
            CustomerValue.HIGH -> 20
            CustomerValue.MEDIUM -> 10
            CustomerValue.NEW -> 0
        }
        
        // Recent price increases hurt negotiating position
        score -= priceIncreases.size * 5
        
        // Competition availability
        if (!hasCompetitors) score -= 15
        
        return when {
            score >= 80 -> NegotiationPower.STRONG
            score >= 60 -> NegotiationPower.MODERATE
            score >= 40 -> NegotiationPower.WEAK
            else -> NegotiationPower.POOR
        }
    }
    
    private fun generateNegotiationScript(
        subscription: ManualRecurringExpense,
        marketRate: MarketRate,
        negotiationPower: NegotiationPower
    ): NegotiationScript {
        val provider = marketRate.providerName
        val currentPrice = subscription.amount
        val competitivePrice = marketRate.competitivePrice
        
        val opening = when (negotiationPower) {
            NegotiationPower.STRONG -> 
                "I've been a loyal customer for a while now, but I've noticed my bill has increased significantly. " +
                "I see that ${marketRate.competitors.firstOrNull() ?: "competitors"} are offering similar services for €${String.format("%.2f", competitivePrice)}/month."
            
            NegotiationPower.MODERATE ->
                "I'm reviewing my monthly expenses and noticed I'm paying €${String.format("%.2f", currentPrice)}. " +
                "I'd like to discuss options to reduce my monthly cost."
            
            else ->
                "I'm looking to reduce my monthly expenses. Are there any promotions or discounts available for existing customers?"
        }
        
        val talkingPoints = listOfNotNull(
            "I've been a customer for ${if (negotiationPower == NegotiationPower.STRONG) "several years" else "some time"}",
            "My current rate is €${String.format("%.2f", currentPrice)} which is above the market average of €${String.format("%.2f", marketRate.averagePrice)}",
            if (marketRate.competitors.isNotEmpty()) "${marketRate.competitors.take(2).joinToString(" and ")} are offering lower rates" else null,
            "I'm considering my options but would prefer to stay with $provider if we can find a better rate",
            "What retention offers do you have available?"
        )
        
        val close = "If we can get closer to €${String.format("%.2f", competitivePrice)}/month, I'm ready to commit today. Otherwise, I may need to explore other options."
        
        return NegotiationScript(
            opening = opening,
            talkingPoints = talkingPoints,
            close = close,
            fallbackAsk = "Can you at least match your new customer promotional rate?"
        )
    }
    
    private fun generateRetentionOffers(
        marketRate: MarketRate,
        negotiationPower: NegotiationPower
    ): List<RetentionOffer> {
        val offers = mutableListOf<RetentionOffer>()
        
        // Standard retention offers based on negotiation power
        when (negotiationPower) {
            NegotiationPower.STRONG -> {
                offers.add(RetentionOffer(
                    type = OfferType.PRICE_MATCH,
                    description = "Match competitor rate",
                    discount = marketRate.competitivePrice,
                    duration = "Ongoing"
                ))
                offers.add(RetentionOffer(
                    type = OfferType.BUNDLE_DISCOUNT,
                    description = "Bundle with mobile for 20% off both",
                    discountPercent = 20.0,
                    duration = "12 months"
                ))
            }
            NegotiationPower.MODERATE -> {
                offers.add(RetentionOffer(
                    type = OfferType.LOYALTY_DISCOUNT,
                    description = "Loyalty discount",
                    discountPercent = 10.0,
                    duration = "12 months"
                ))
                offers.add(RetentionOffer(
                    type = OfferType.PROMO_RATE,
                    description = "New customer promotional rate",
                    discount = marketRate.competitivePrice,
                    duration = "6 months"
                ))
            }
            else -> {
                offers.add(RetentionOffer(
                    type = OfferType.PROMO_RATE,
                    description = "Limited time promotional rate",
                    discountPercent = 5.0,
                    duration = "3 months"
                ))
            }
        }
        
        return offers
    }
    
    private fun calculateSuccessProbability(
        negotiationPower: NegotiationPower,
        marketRate: MarketRate
    ): Int {
        val base = when (negotiationPower) {
            NegotiationPower.STRONG -> 85
            NegotiationPower.MODERATE -> 65
            NegotiationPower.WEAK -> 45
            NegotiationPower.POOR -> 25
        }
        
        // Adjust based on competition
        val competitionBonus = if (marketRate.competitors.isNotEmpty()) 10 else -10
        
        return (base + competitionBonus).coerceIn(0, 95)
    }
    
    private fun determineRecommendedAction(
        negotiationPower: NegotiationPower,
        potentialSavings: Double,
        marketRate: MarketRate
    ): RecommendedAction {
        return when {
            potentialSavings < 5.0 -> RecommendedAction.ACCEPT_CURRENT_RATE
            negotiationPower == NegotiationPower.STRONG && potentialSavings > 10 -> 
                RecommendedAction.NEGOTIATE_AGGRESSIVELY
            negotiationPower in listOf(NegotiationPower.STRONG, NegotiationPower.MODERATE) ->
                RecommendedAction.CALL_RETENTION
            marketRate.competitors.isNotEmpty() ->
                RecommendedAction.THREATEN_SWITCH
            else ->
                RecommendedAction.ACCEPT_SMALL_DISCOUNT
        }
    }
    
    private fun normalizeMerchantName(name: String): String {
        return name.uppercase()
            .replace(Regex("[^A-ZΑ-Ω0-9]"), "")
            .replace(Regex("ΑΣΦΑΛΕΙΑ|INSURANCE|ΑΣΦ"), "INSURANCE")
            .replace(Regex("ΚΙΝΗΤΗ|MOBILE|CELL"), "MOBILE")
            .replace(Regex("ΙΝΤΕΡΝΕΤ|INTERNET"), "INTERNET")
    }
    
    suspend fun recordNegotiationOutcome(
        subscriptionId: Long,
        outcome: NegotiationOutcome,
        newPrice: Double?,
        savings: Double?,
        notes: String?
    ) {
        // This would save to a negotiation history table
        // For now, this is a placeholder for the tracking functionality
    }
    
    data class MarketRate(
        val serviceType: ServiceType,
        val providerName: String,
        val averagePrice: Double,
        val competitivePrice: Double,
        val bestPrice: Double,
        val unit: String,
        val competitors: List<String>
    )
    
    data class NegotiationOpportunity(
        val subscriptionId: Long,
        val serviceName: String,
        val serviceType: ServiceType,
        val currentProvider: String,
        val currentPrice: Double,
        val billingCycle: String,
        val marketAveragePrice: Double,
        val competitivePrice: Double,
        val bestAvailablePrice: Double,
        val potentialMonthlySavings: Double,
        val potentialYearlySavings: Double,
        val savingsPercent: Double,
        val negotiationPower: NegotiationPower,
        val priceIncreaseCount: Int,
        val lastPriceIncrease: Long?,
        val alternativeProviders: List<String>,
        val negotiationScript: NegotiationScript,
        val retentionOffers: List<RetentionOffer>,
        val successProbability: Int,
        val recommendedAction: RecommendedAction
    )
    
    enum class ServiceType {
        INTERNET, MOBILE, STREAMING, INSURANCE, ENERGY, WATER, GYM, SOFTWARE, OTHER
    }
    
    enum class NegotiationPower {
        STRONG, MODERATE, WEAK, POOR
    }
    
    enum class CustomerValue {
        HIGH, MEDIUM, NEW
    }
    
    enum class RecommendedAction {
        NEGOTIATE_AGGRESSIVELY,
        CALL_RETENTION,
        THREATEN_SWITCH,
        ACCEPT_SMALL_DISCOUNT,
        ACCEPT_CURRENT_RATE
    }
    
    data class PriceIncrease(
        val date: Long,
        val oldPrice: Double,
        val newPrice: Double,
        val increaseAmount: Double,
        val increasePercent: Double
    )
    
    data class NegotiationScript(
        val opening: String,
        val talkingPoints: List<String>,
        val close: String,
        val fallbackAsk: String
    )
    
    data class RetentionOffer(
        val type: OfferType,
        val description: String,
        val discount: Double? = null,
        val discountPercent: Double? = null,
        val duration: String
    )
    
    enum class OfferType {
        PRICE_MATCH, LOYALTY_DISCOUNT, PROMO_RATE, BUNDLE_DISCOUNT, FREE_MONTHS
    }
    
    data class NegotiationOutcome(
        val success: Boolean,
        val newMonthlyRate: Double?,
        val outcomeType: OutcomeType,
        val notes: String?
    )
    
    enum class OutcomeType {
        SUCCESSFUL_NEGOTIATION,
        PARTIAL_SUCCESS,
        NO_CHANGE,
        SWITCHED_PROVIDER,
        CANCELLED
    }
}
