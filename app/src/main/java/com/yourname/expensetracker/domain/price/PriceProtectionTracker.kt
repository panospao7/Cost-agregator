package com.yourname.expensetracker.domain.price

import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks items eligible for price protection, deal hunting, coupon matching,
 * and credit card benefits.
 *
 * Database access to receipt data goes through [ReceiptRepository] — NOT through
 * [com.yourname.expensetracker.data.database.dao.ScannedReceiptDao] directly.
 * This ensures all receipt lifecycle invariants are respected.
 */
@Singleton
class PriceProtectionTracker @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val timeProvider: TimeProvider
) {
    
    // Track items eligible for price protection
    suspend fun getPriceProtectedItems(): List<PriceProtectedItem> {
        val now = timeProvider.now()
        val since = TimePeriodUtils.getLastNCalendarDaysRange(now, 30).first
        val recentReceipts = receiptRepository.getRecentReceipts(since)
        
        return recentReceipts
            .filter { receipt ->
                // Only include RETAIL_RECEIPT and EMAIL_RECEIPT document types
                receipt.documentType == "RETAIL_RECEIPT" || receipt.documentType == "EMAIL_RECEIPT"
            }
            .flatMap { receipt ->
                parsePriceProtectedItems(receipt) ?: emptyList()
            }
    }
    
    /**
     * Parses receipt items and maps them to price-protectable items.
     * Uses [ScannedReceipt.parsedDate] as the authoritative purchase date
     * (from OCR extraction), falling back to [ScannedReceipt.createdAt]
     * (scan timestamp) when parsed date is unavailable.
     */
    private fun parsePriceProtectedItems(receipt: ScannedReceipt): List<PriceProtectedItem>? {
        // Parse receipt items that might be eligible for price protection
        // This would typically use AI to extract item details from the receipt
        
        val purchaseDate = receipt.parsedDate ?: receipt.createdAt
        val receiptCurrency = receipt.currency
        return receipt.parsedItems?.let { itemsJson ->
            parseExtractedItems(itemsJson)?.mapNotNull { item ->
                if (isPriceProtectable(item)) {
                    PriceProtectedItem(
                        receiptId = receipt.id,
                        itemName = item.name,
                        merchant = receipt.parsedMerchant ?: "Unknown",
                        purchasePrice = item.price,
                        purchaseCurrency = receiptCurrency,
                        purchaseDate = purchaseDate,
                        category = item.category,
                        priceProtectionEligible = isEligibleForPriceProtection(receipt),
                        returnWindowDays = getReturnWindow(receipt.parsedMerchant),
                        currentBestPrice = null, // Would be fetched from price APIs
                        currentBestPriceCurrency = null,
                        priceHistory = emptyList() // Would be tracked over time
                    )
                } else null
            }
        } ?: emptyList()
    }
    
    private fun isPriceProtectable(item: ExtractedItem): Boolean {
        // Items that typically have price protection
        val priceProtectedCategories = listOf(
            "electronics", "appliances", "computers", "phones", "tv", 
            "camera", "furniture", "tools", "sports", "outdoor"
        )
        
        return priceProtectedCategories.any { category ->
            item.category?.contains(category, ignoreCase = true) == true ||
            item.name.contains(category, ignoreCase = true)
        }
    }
    
    private fun isEligibleForPriceProtection(receipt: ScannedReceipt): Boolean {
        // Check if within price protection window (usually 14-30 days)
        // Use parsedDate (OCR-extracted purchase date) when available, fall back to scan timestamp
        val purchaseDate = receipt.parsedDate ?: receipt.createdAt
        val daysSincePurchase = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(purchaseDate),
            Instant.ofEpochMilli(timeProvider.now())
        )
        
        // Most retailers offer 14-30 day price protection
        return daysSincePurchase <= 30
    }
    
    private fun parseExtractedItems(jsonItems: String): List<ExtractedItem>? {
        return try {
            if (jsonItems.isBlank()) {
                return emptyList()
            }

            val array = JSONArray(jsonItems)
            (0 until array.length()).mapNotNull { index ->
                val element = array.opt(index)

                when (element) {
                    is JSONObject -> {
                        val name = element.optString("name")
                            .ifBlank { element.optString("itemName") }
                            .ifBlank { null }
                            ?: return@mapNotNull null

                        val price = parseDoubleField(
                            element,
                            primaryKey = "price",
                            fallbackKeys = listOf("amount", "total", "unitPrice")
                        ) ?: return@mapNotNull null

                        val category = element.optString("category").takeIf { it.isNotBlank() }

                        val quantity = element.optInt("quantity", 1)
                            .takeIf { it > 0 }
                            ?: element.optDouble("quantity", 1.0).toInt().coerceAtLeast(1)

                        ExtractedItem(
                            name = name,
                            price = price,
                            category = category,
                            quantity = quantity
                        )
                    }
                    is String -> {
                        val trimmed = element.trim()
                        if (trimmed.isNotBlank()) {
                            ExtractedItem(
                                name = trimmed,
                                price = 0.0,
                                category = null,
                                quantity = 1
                            )
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDoubleField(
        jsonObject: JSONObject,
        primaryKey: String,
        fallbackKeys: List<String>
    ): Double? {
        val keys = listOf(primaryKey) + fallbackKeys
        for (key in keys) {
            if (!jsonObject.has(key)) continue

            when (val value = jsonObject.opt(key)) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }
    
    private fun getReturnWindow(merchantName: String?): Int {
        return when {
            merchantName == null -> 14
            merchantName.contains("amazon", ignoreCase = true) -> 30
            merchantName.contains("costco", ignoreCase = true) -> 90
            merchantName.contains("walmart", ignoreCase = true) -> 90
            merchantName.contains("target", ignoreCase = true) -> 90
            merchantName.contains("best buy", ignoreCase = true) -> 15
            merchantName.contains("apple", ignoreCase = true) -> 14
            else -> 30
        }
    }
    
    // Simulate price monitoring (in production, would use real price APIs)
    fun monitorPriceDrops(): Flow<List<PriceDropAlert>> = flow {
        val protectedItems = getPriceProtectedItems()
        
        val alerts = protectedItems.mapNotNull { item ->
            // Check for price drops
            val currentPrice = getCurrentPrice(item)
            
            if (currentPrice != null && currentPrice < item.purchasePrice) {
                val savings = item.purchasePrice - currentPrice
                val savingsPercent = (savings / item.purchasePrice * 100)
                
                if (savingsPercent >= 5) { // Only alert for 5%+ drops
                    PriceDropAlert(
                        item = item,
                        currentPrice = currentPrice,
                        priceDrop = savings,
                        priceDropPercent = savingsPercent,
                        claimUrl = generateClaimUrl(item),
                        daysRemaining = ChronoUnit.DAYS.between(
                            Instant.ofEpochMilli(timeProvider.now()),
                            Instant.ofEpochMilli(item.purchaseDate).plus(30, ChronoUnit.DAYS)
                        ).toInt(),
                        isSimulated = true
                    )
                } else null
            } else null
        }
        
        emit(alerts)
    }
    
    private fun getCurrentPrice(item: PriceProtectedItem): Double? {
        // In production, this would query price comparison APIs
        // For now, simulate occasional price drops
        return when {
            item.itemName.contains("electronics", ignoreCase = true) -> 
                item.purchasePrice * 0.92 // 8% drop simulation
            item.itemName.contains("appliance", ignoreCase = true) -> 
                item.purchasePrice * 0.95 // 5% drop simulation
            else -> null
        }
    }
    
    private fun generateClaimUrl(item: PriceProtectedItem): String? {
        // Generate price protection claim URLs for major retailers
        return when {
            item.merchant.contains("amazon", ignoreCase = true) -> 
                "https://www.amazon.com/gp/help/customer/contact-us"
            item.merchant.contains("best buy", ignoreCase = true) -> 
                "https://www.bestbuy.com/site/help-topics/price-match-guarantee/pcmcat290600050002.c"
            item.merchant.contains("target", ignoreCase = true) -> 
                "https://help.target.com/help/PriceMatchPolicy"
            item.merchant.contains("walmart", ignoreCase = true) -> 
                "https://www.walmart.com/cp/walmart-protection-plans"
            else -> null
        }
    }
    
    // Deal Hunting
    suspend fun findBetterDeals(receipt: ScannedReceipt): List<DealAlternative> {
        val items = parseExtractedItems(receipt.parsedItems ?: "") ?: return emptyList()
        
        return items.mapNotNull { item ->
            val betterDeal = findBetterPrice(item)
            
            if (betterDeal != null && betterDeal.price < item.price * 0.9) { // 10%+ savings
                DealAlternative(
                    originalItem = item,
                    originalPrice = item.price,
                    betterMerchant = betterDeal.merchant,
                    betterPrice = betterDeal.price,
                    savings = item.price - betterDeal.price,
                    savingsPercent = ((item.price - betterDeal.price) / item.price * 100),
                    dealUrl = betterDeal.url,
                    expiresAt = betterDeal.expiresAt,
                    isSimulated = true
                )
            } else null
        }
    }
    
    private fun findBetterPrice(item: ExtractedItem): PriceResult? {
        // In production, would search deal APIs, price comparison sites
        // Simulated better deal
        if (item.price > 100) { // Only for higher priced items
            return PriceResult(
                merchant = "Competitor Store",
                price = item.price * 0.85, // 15% cheaper
                url = "https://example.com/deal",
                expiresAt = timeProvider.now() + (7 * 24 * 60 * 60 * 1000) // 7 days
            )
        }
        return null
    }
    
    // Coupon Matching
    suspend fun findCoupons(receipt: ScannedReceipt): List<CouponMatch> {
        val merchant = receipt.parsedMerchant ?: return emptyList()
        
        // In production, would search coupon databases
        // Simulated coupons
        return listOf(
            CouponMatch(
                merchant = merchant,
                code = "SAVE10",
                description = "10% off your next purchase",
                discount = 10.0,
                discountType = DiscountType.PERCENTAGE,
                minPurchase = 50.0,
                expiresAt = timeProvider.now() + (14 * 24 * 60 * 60 * 1000),
                url = "https://example.com/coupons",
                isSimulated = true
            )
        ).filter { it.merchant.contains(merchant, ignoreCase = true) || merchant.contains(it.merchant, ignoreCase = true) }
    }
    
    // Credit Card Benefits
    fun getCreditCardBenefits(receipt: ScannedReceipt): List<CreditCardBenefit> {
        val total = receipt.parsedTotal ?: return emptyList()
        val merchant = receipt.parsedMerchant ?: return emptyList()
        
        return when {
            isRestaurant(merchant) -> listOf(
                CreditCardBenefit(
                    cardName = "Dining Rewards Card",
                    benefitType = BenefitType.CASHBACK,
                    benefitDescription = "3% cashback on dining",
                    estimatedValue = total * 0.03,
                    requiresAction = false
                )
            )
            isGroceryStore(merchant) -> listOf(
                CreditCardBenefit(
                    cardName = "Grocery Rewards Card",
                    benefitType = BenefitType.CASHBACK,
                    benefitDescription = "5% cashback on groceries",
                    estimatedValue = total * 0.05,
                    requiresAction = false
                )
            )
            isGasStation(merchant) -> listOf(
                CreditCardBenefit(
                    cardName = "Gas Rewards Card",
                    benefitType = BenefitType.CASHBACK,
                    benefitDescription = "4% cashback on gas",
                    estimatedValue = total * 0.04,
                    requiresAction = false
                )
            )
            total > 500 -> listOf(
                CreditCardBenefit(
                    cardName = "Purchase Protection",
                    benefitType = BenefitType.PROTECTION,
                    benefitDescription = "Extended warranty + theft protection",
                    estimatedValue = total * 0.02, // Estimated value of protection
                    requiresAction = true,
                    actionDescription = "Keep receipt for 90 days"
                )
            )
            else -> emptyList()
        }
    }
    
    private fun isRestaurant(merchant: String): Boolean {
        return merchant.containsAny(
            "restaurant", "cafe", "coffee", "dining", "tavern", "grill", 
            "kitchen", "bistro", "eatery", "food", "pizza", "sushi"
        )
    }
    
    private fun isGroceryStore(merchant: String): Boolean {
        return merchant.containsAny(
            "grocery", "supermarket", "market", "sklavenitis", "ab", "lidl", 
            "carrefour", "masoutis", "my market", "veropoulos"
        )
    }
    
    private fun isGasStation(merchant: String): Boolean {
        return merchant.containsAny(
            "gas", "fuel", "shell", "bp", "esso", "mobil", "petroleum", 
            "bενζίνη", "πρατήριο", "eko", "revoil", "bp"
        )
    }
    
    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it, ignoreCase = true) }
    }

    suspend fun getDealsCouponsAndBenefits(): DealsAndBenefits {
        val recentReceipts = receiptRepository.getRecentReceipts(since = 0L, limit = 20)

        // Only include RETAIL_RECEIPT and EMAIL_RECEIPT document types
        val filteredReceipts = recentReceipts.filter { receipt ->
            receipt.documentType == "RETAIL_RECEIPT" || receipt.documentType == "EMAIL_RECEIPT"
        }

        val deals = mutableListOf<DealAlternative>()
        val coupons = mutableListOf<CouponMatch>()
        val benefits = mutableListOf<CreditCardBenefit>()

        filteredReceipts.forEach { receipt ->
            deals += findBetterDeals(receipt)
            coupons += findCoupons(receipt)
            benefits += getCreditCardBenefits(receipt)
        }

        val deduplicatedDeals = deals
            .groupBy { "${it.originalItem.name.lowercase()}-${it.betterMerchant.lowercase()}" }
            .mapNotNull { (_, variants) -> variants.maxByOrNull { it.savings } }
            .sortedByDescending { it.savings }

        val deduplicatedCoupons = coupons
            .groupBy { "${it.merchant.lowercase()}-${it.code.lowercase()}" }
            .mapNotNull { (_, variants) -> variants.maxByOrNull { it.discount } }
            .sortedByDescending { it.discount }

        val deduplicatedBenefits = benefits
            .groupBy { "${it.cardName.lowercase()}-${it.benefitType}-${it.benefitDescription.lowercase()}" }
            .mapNotNull { (_, variants) -> variants.maxByOrNull { it.estimatedValue } }
            .sortedByDescending { it.estimatedValue }

        return DealsAndBenefits(
            deals = deduplicatedDeals,
            coupons = deduplicatedCoupons,
            benefits = deduplicatedBenefits
        )
    }
    
    data class PriceProtectedItem(
        val receiptId: Long,
        val itemName: String,
        val merchant: String,
        val purchasePrice: Double,
        val purchaseCurrency: String = "EUR",
        val purchaseDate: Long,
        val category: String?,
        val priceProtectionEligible: Boolean,
        val returnWindowDays: Int,
        val currentBestPrice: Double?,
        val currentBestPriceCurrency: String? = null,
        val priceHistory: List<PricePoint>
    )
    
    data class PricePoint(
        val price: Double,
        val timestamp: Long,
        val source: String
    )
    
    data class PriceDropAlert(
        val item: PriceProtectedItem,
        val currentPrice: Double,
        val currentPriceCurrency: String = item.purchaseCurrency,
        val priceDrop: Double,
        val priceDropPercent: Double,
        val claimUrl: String?,
        val daysRemaining: Int,
        val isSimulated: Boolean = false
    )
    
    data class DealAlternative(
        val originalItem: ExtractedItem,
        val originalPrice: Double,
        val betterMerchant: String,
        val betterPrice: Double,
        val savings: Double,
        val savingsPercent: Double,
        val dealUrl: String,
        val expiresAt: Long,
        val isSimulated: Boolean = false
    )
    
    data class ExtractedItem(
        val name: String,
        val price: Double,
        val category: String?,
        val quantity: Int = 1
    )
    
    data class PriceResult(
        val merchant: String,
        val price: Double,
        val url: String,
        val expiresAt: Long
    )
    
    data class CouponMatch(
        val merchant: String,
        val code: String,
        val description: String,
        val discount: Double,
        val discountType: DiscountType,
        val minPurchase: Double?,
        val expiresAt: Long,
        val url: String,
        val isSimulated: Boolean = false
    )
    
    enum class DiscountType {
        PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING, BOGO
    }
    
    data class CreditCardBenefit(
        val cardName: String,
        val benefitType: BenefitType,
        val benefitDescription: String,
        val estimatedValue: Double,
        val requiresAction: Boolean,
        val actionDescription: String? = null
    )
    
    enum class BenefitType {
        CASHBACK, POINTS, PROTECTION, WARRANTY, EXTENDED_RETURN
    }

    data class DealsAndBenefits(
        val deals: List<DealAlternative>,
        val coupons: List<CouponMatch>,
        val benefits: List<CreditCardBenefit>
    )
}
