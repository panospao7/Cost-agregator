package com.yourname.expensetracker.domain.negotiation

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.SubscriptionPriceHistoryDao
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.NegotiationOutcomeEntity
import com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class SmartBillNegotiationEngine @Inject constructor(
    private val recurringExpenseRepository: RecurringExpenseRepository,
    private val priceHistoryDao: SubscriptionPriceHistoryDao,
    private val marketRateProvider: MarketRateProvider,
    private val database: AppDatabase,
    private val writeBarrier: DatabaseWriteBarrier,
    private val timeProvider: TimeProvider
) {
    
    
    suspend fun analyzeNegotiationOpportunities(): List<NegotiationOpportunity> {
        val subscriptions = recurringExpenseRepository.getAll()
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
    
    private suspend fun findMarketRate(merchantName: String): MarketRate? {
        val serviceType = detectServiceType(merchantName) ?: return null
        val providerType = mapServiceTypeToProviderEnum(serviceType)
        val result = try {
            marketRateProvider.getRates(
                serviceType = providerType,
                region = "GR",
                currency = "EUR"
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "MarketRateProvider failed for merchant=$merchantName, serviceType=$serviceType")
            return null
        }
        val quotes = result.quotes
        if (quotes.isEmpty()) return null

        val upperName = merchantName.uppercase()
        val bestQuote = quotes.firstOrNull { quote ->
            upperName.contains(quote.providerName.uppercase()) ||
            quote.providerName.uppercase().contains(upperName)
        } ?: quotes.first()

        return MarketRate(
            serviceType = serviceType,
            providerName = bestQuote.providerName,
            averagePrice = bestQuote.averageMonthlyPrice,
            competitivePrice = bestQuote.competitiveMonthlyPrice,
            bestPrice = bestQuote.bestMonthlyPrice,
            unit = "month",
            competitors = quotes.filter { it != bestQuote }.map { it.providerName },
            lastUpdated = result.lastUpdatedAt
        )
    }

    private fun mapServiceTypeToProviderEnum(
        engineType: ServiceType
    ): com.yourname.expensetracker.domain.negotiation.ServiceType {
        return when (engineType) {
            ServiceType.STREAMING -> com.yourname.expensetracker.domain.negotiation.ServiceType.STREAMING
            ServiceType.INSURANCE -> com.yourname.expensetracker.domain.negotiation.ServiceType.INSURANCE
            ServiceType.GYM -> com.yourname.expensetracker.domain.negotiation.ServiceType.GYM
            ServiceType.SOFTWARE -> com.yourname.expensetracker.domain.negotiation.ServiceType.CLOUD_STORAGE
            ServiceType.INTERNET -> com.yourname.expensetracker.domain.negotiation.ServiceType.INTERNET
            ServiceType.MOBILE -> com.yourname.expensetracker.domain.negotiation.ServiceType.MOBILE
            ServiceType.ENERGY -> com.yourname.expensetracker.domain.negotiation.ServiceType.ENERGY
            ServiceType.WATER -> com.yourname.expensetracker.domain.negotiation.ServiceType.WATER
            else -> com.yourname.expensetracker.domain.negotiation.ServiceType.OTHER
        }
    }
    
    /**
     * WRN-31-FIXED: Priority ordering to prevent misclassification.
     *
     * **Problem:** COSMOTE, VODAFONE, WIND appear in both INTERNET and MOBILE
     * keyword lists. The previous ordering checked INTERNET first, so a merchant
     * named "COSMOTE MOBILE" would be misclassified as INTERNET.
     *
     * **Fix:** MOBILE is now checked BEFORE INTERNET. The most specific keywords
     * (MOBILE, CELL, PHONE) are tried first, so multi-service providers are
     * classified under their most specific service type. Only if no MOBILE
     * keywords match do we fall through to INTERNET.
     *
     * For future robustness, consider a scoring approach where the service type
     * with the most keyword matches wins, rather than first-match-wins.
     */
    private fun detectServiceType(merchantName: String): ServiceType? {
        val name = merchantName.uppercase()
        return when {
            // MOBILE checked first to avoid misclassifying mobile-specific
            // entries (e.g. "COSMOTE MOBILE") as INTERNET.
            name.containsAny("MOBILE", "CELL", "PHONE", "COSMOTE MOBILE", "VODAFONE CU", "WHAT'S UP") -> ServiceType.MOBILE
            name.containsAny("INTERNET", "FIBER", "VDSL", "BROADBAND", "COSMOTE", "VODAFONE", "WIND", "NOVA") -> ServiceType.INTERNET
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
    
    /**
     * Normalizes a billing amount to its monthly equivalent based on frequency.
     *
     * I7: Savings calculations must be on a common monthly basis so that
     * weekly, quarterly, annual, etc. subscriptions are compared fairly against
     * market rates which are always quoted per month.
     */
    private fun monthlyEquivalent(amount: Double, frequency: RecurrenceFrequency): Double {
        if (amount <= 0.0) return 0.0
        val months = frequency.calendarMonths
        if (months != null) {
            return amount / months
        }
        // Fixed-interval frequencies (weekly, biweekly)
        val days = frequency.fixedIntervalDays
        if (days != null) {
            return amount * (365.0 / 12.0) / days // avg days per month = 365/12 ≈ 30.42
        }
        // IRREGULAR: cannot determine — return raw amount as best-effort
        return amount
    }

    /**
     * PR8: Converts a monthly price back to the billing-cycle amount
     * appropriate for the given frequency. This is the inverse operation
     * of [monthlyEquivalent] and ensures that after a successful
     * negotiation the subscription's stored amount remains in
     * billing-cycle terms (e.g. €84/year for an annual subscription).
     */
    private fun convertFromMonthlyEquivalent(monthlyPrice: Double, frequency: RecurrenceFrequency): Double {
        val months = frequency.calendarMonths
        if (months != null) return monthlyPrice * months
        val days = frequency.fixedIntervalDays
        if (days != null) return monthlyPrice * days / (365.0 / 12.0)
        return monthlyPrice // IRREGULAR fallback
    }

    /**
     * WRN-29-FIXED: billing frequency normalization.
     * The [monthlyEquivalent] method converts any billing frequency
     * (weekly, biweekly, quarterly, annual, etc.) to a monthly amount
     * before comparing against market rates. This ensures that an annual
     * subscription of €120 is correctly compared as €10/month rather than
     * appearing to be far above a €25/month market rate.
     */
    private fun createNegotiationOpportunity(
        subscription: ManualRecurringExpense,
        marketRate: MarketRate,
        currentPrice: Double,
        priceHistory: List<com.yourname.expensetracker.data.database.entity.SubscriptionPriceHistory>
    ): NegotiationOpportunity {
        // I7: Normalize current price to monthly equivalent before comparing
        // against market rates (which are denominated per month).
        val monthlyEquivalentPrice = monthlyEquivalent(currentPrice, subscription.frequency)
        val potentialMonthlySavings = monthlyEquivalentPrice - marketRate.competitivePrice
        val savingsPercent = if (monthlyEquivalentPrice > 0) (potentialMonthlySavings / monthlyEquivalentPrice * 100) else 0.0
        
        val priceIncreases = analyzePriceIncreases(priceHistory)
        val customerValue = calculateCustomerValue(subscription, priceHistory.size)
        
        val negotiationPower = calculateNegotiationPower(
            currentPrice = monthlyEquivalentPrice,
            marketRate = marketRate,
            customerValue = customerValue,
            priceIncreases = priceIncreases,
            hasCompetitors = marketRate.competitors.isNotEmpty()
        )
        
        // W09: Compare monthlyEquivalentPrice to monthlyEquivalentPrice, not raw billing-cycle amounts.
        // The NegotiationOpportunity stores currentPrice as the monthly equivalent so that
        // annual/quarterly subscriptions are fairly compared against monthly market rates.
        // The raw billing amount is stored separately in rawBillingAmount for display purposes.
        return NegotiationOpportunity(
            subscriptionId = subscription.id,
            serviceName = subscription.merchant,
            serviceType = marketRate.serviceType,
            currentProvider = marketRate.providerName,
            currentPrice = monthlyEquivalentPrice,
            billingCycle = subscription.frequency.name,
            rawBillingAmount = subscription.amount,
            billingFrequency = subscription.frequency,
            monthlyEquivalentPrice = monthlyEquivalentPrice,
            currency = subscription.currency,
            marketAveragePrice = marketRate.averagePrice,
            competitivePrice = marketRate.competitivePrice,
            bestAvailablePrice = marketRate.bestPrice,
            potentialMonthlySavings = potentialMonthlySavings.coerceAtLeast(0.0),
            potentialYearlySavings = potentialMonthlySavings.coerceAtLeast(0.0) * 12,
            savingsPercent = savingsPercent,
            negotiationPower = negotiationPower,
            priceIncreaseCount = priceIncreases.size,
            lastPriceIncrease = priceHistory.maxByOrNull { it.recordedAt }?.recordedAt,
            alternativeProviders = marketRate.competitors,
            negotiationScript = generateNegotiationScript(
                subscription = subscription,
                marketRate = marketRate,
                negotiationPower = negotiationPower,
                monthlyEquivalentPrice = monthlyEquivalentPrice
            ),
            retentionOffers = generateRetentionOffers(marketRate, negotiationPower),
            successProbability = calculateSuccessProbability(negotiationPower, marketRate),
            recommendedAction = determineRecommendedAction(
                negotiationPower,
                potentialMonthlySavings,
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
        negotiationPower: NegotiationPower,
        monthlyEquivalentPrice: Double
    ): NegotiationScript {
        val provider = marketRate.providerName
        val rawBillingAmount = subscription.amount
        val monthlyPrice = monthlyEquivalentPrice
        val competitivePrice = marketRate.competitivePrice
        
        val opening = when (negotiationPower) {
            NegotiationPower.STRONG -> 
                "I've been a loyal customer for a while now, but I've noticed my bill has increased significantly. " +
                "I see that ${marketRate.competitors.firstOrNull() ?: "competitors"} are offering similar services for ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", competitivePrice)}/month."
            
            NegotiationPower.MODERATE -> {
                val priceText = if (subscription.frequency != com.yourname.expensetracker.domain.model.RecurrenceFrequency.MONTHLY) {
                    "${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", monthlyPrice)}/month " +
                    "(currently ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", rawBillingAmount)} every ${subscription.frequency.name.lowercase().replace("_", " ")})"
                } else {
                    "${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", monthlyPrice)}/month"
                }
                "I'm reviewing my monthly expenses and noticed I'm paying $priceText. " +
                "I'd like to discuss options to reduce my monthly cost."
            }
            
            else ->
                "I'm looking to reduce my monthly expenses. Are there any promotions or discounts available for existing customers?"
        }
        
        val talkingPoints = listOfNotNull(
            "I've been a customer for ${if (negotiationPower == NegotiationPower.STRONG) "several years" else "some time"}",
            "My current rate is ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", monthlyPrice)} which is above the market average of ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", marketRate.averagePrice)}",
            if (marketRate.competitors.isNotEmpty()) "${marketRate.competitors.take(2).joinToString(" and ")} are offering lower rates" else null,
            "I'm considering my options but would prefer to stay with $provider if we can find a better rate",
            "What retention offers do you have available?"
        )
        
        val close = "If we can get closer to ${CurrencyFormatter.getCurrencySymbol(subscription.currency)}${String.format("%.2f", competitivePrice)}/month, I'm ready to commit today. Otherwise, I may need to explore other options."
        
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
    
    /**
     * Returns all persisted negotiation outcomes from the database.
     */
    suspend fun getNegotiationHistory(): List<NegotiationOutcomeEntity> {
        return database.negotiationOutcomeDao().getAll()
    }

    suspend fun recordNegotiationOutcome(
        subscriptionId: Long,
        outcome: NegotiationOutcome,
        newPrice: Double?,
        savings: Double?,
        notes: String?
    ): Result<Unit> {
        writeBarrier.checkWritesAllowed("SmartBillNegotiationEngine.recordNegotiationOutcome")

        val subscription = recurringExpenseRepository.getById(subscriptionId)
            ?: return Result.failure(IllegalArgumentException("Subscription not found: $subscriptionId"))

        val now = timeProvider.now()

        return try {
            database.withTransaction {
                // 1. Insert negotiation outcome
                val outcomeEntity = NegotiationOutcomeEntity(
                    subscriptionId = subscriptionId,
                    outcome = outcome.name,
                    oldAmount = subscription.amount,
                    newAmount = newPrice,
                    currency = subscription.currency,
                    savingsAmount = savings,
                    notes = notes,
                    marketRateSource = "StaticMarketRateProvider",
                    createdAt = now
                )
                database.negotiationOutcomeDao().insert(outcomeEntity)

                // 2. If success/partial and newPrice is valid, update subscription + price history
                if ((outcome == NegotiationOutcome.SUCCESS || outcome == NegotiationOutcome.PARTIAL)
                    && newPrice != null && newPrice > 0
                ) {
                    // PR8: Convert monthly newPrice back to billing-cycle amount before storing,
                    // so that non-monthly subscriptions (annual, quarterly, etc.) are not corrupted.
                    val newBillingAmount = convertFromMonthlyEquivalent(
                        monthlyPrice = newPrice,
                        frequency = subscription.frequency
                    )
                    priceHistoryDao.insert(
                        SubscriptionPriceHistory(
                            subscriptionId = subscriptionId,
                            amount = newBillingAmount,
                            currency = subscription.currency,
                            recordedAt = now,
                            changeReason = "Negotiation outcome: ${outcome.name}"
                        )
                    )

                    recurringExpenseRepository.update(
                        subscription.copy(amount = newBillingAmount)
                    )
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Failed to record negotiation outcome for subscriptionId=$subscriptionId")
            Result.failure(e)
        }
    }
    
    data class MarketRate(
        val serviceType: ServiceType,
        val providerName: String,
        val averagePrice: Double,
        val competitivePrice: Double,
        val bestPrice: Double,
        val unit: String,
        val competitors: List<String>,
        /** Epoch millis when this rate was last refreshed. */
        val lastUpdated: Long = 0L
    ) {
        /**
         * WRN-28-FIXED: Staleness check.
         * Returns `true` if this rate has not been updated within [maxAgeDays].
         */
        fun isStale(now: Long, maxAgeDays: Int = 30): Boolean {
            if (lastUpdated <= 0L) return true
            return (now - lastUpdated) > TimeUnit.DAYS.toMillis(maxAgeDays.toLong())
        }
    }
    
    data class NegotiationOpportunity(
        val subscriptionId: Long,
        val serviceName: String,
        val serviceType: ServiceType,
        val currentProvider: String,
        val currentPrice: Double,
        val billingCycle: String,
        val rawBillingAmount: Double,
        val billingFrequency: com.yourname.expensetracker.domain.model.RecurrenceFrequency,
        val monthlyEquivalentPrice: Double,
        val currency: String,
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
    
    enum class NegotiationOutcome {
        SUCCESS,
        PARTIAL,
        FAILURE
    }
    
    enum class OutcomeType {
        SUCCESSFUL_NEGOTIATION,
        PARTIAL_SUCCESS,
        NO_CHANGE,
        SWITCHED_PROVIDER,
        CANCELLED
    }
}
