package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enhanced merchant extraction with intelligent matching from existing merchant database.
 */
@Singleton
class EnhancedMerchantExtractor @Inject constructor(
    private val merchantNormalizationDao: MerchantNormalizationDao
) {
    companion object {
        const val MIN_CONFIDENCE_THRESHOLD = 0.7
        const val MAX_EDIT_DISTANCE = 3
    }

    /**
     * Extract merchant name from OCR text with enhanced matching.
     */
    suspend fun extractMerchant(
        ocrText: String,
        existingMerchant: String? = null
    ): MerchantExtractionResult = withContext(Dispatchers.IO) {
        val candidates = extractMerchantCandidates(ocrText)
        
        // If we already have a merchant from another source (e.g., GPS), verify it
        if (existingMerchant != null) {
            val verifiedMerchant = verifyExistingMerchant(existingMerchant, candidates)
            if (verifiedMerchant != null) {
                return@withContext MerchantExtractionResult(
                    merchantName = verifiedMerchant,
                    confidence = 0.9,
                    source = "verified_existing",
                    alternatives = candidates.filter { it != verifiedMerchant }.take(3)
                )
            }
        }
        
        // Try to match against known merchants
        val bestMatch = findBestMerchantMatch(candidates)
        if (bestMatch != null) {
            return@withContext MerchantExtractionResult(
                merchantName = bestMatch.canonicalName,
                confidence = bestMatch.confidence,
                source = "database_match",
                alternatives = candidates.filter { it != bestMatch.canonicalName }.take(3)
            )
        }
        
        // Return the most likely candidate from OCR
        val bestCandidate = candidates.firstOrNull()
        if (bestCandidate != null) {
            return@withContext MerchantExtractionResult(
                merchantName = bestCandidate,
                confidence = 0.6,
                source = "ocr_extraction",
                alternatives = candidates.drop(1).take(3)
            )
        }
        
        // Fallback
        MerchantExtractionResult(
            merchantName = "Unknown Merchant",
            confidence = 0.0,
            source = "fallback",
            alternatives = emptyList()
        )
    }
    
    /**
     * Extract potential merchant names from OCR text.
     */
    private fun extractMerchantCandidates(ocrText: String): List<String> {
        val lines = ocrText.split("\n", "\r")
        val candidates = mutableListOf<String>()
        
        // Common merchant indicators
        val merchantKeywords = listOf(
            "LTD", "INC", "LLC", "GMBH", "SA", "OY", "AB", "AS", "APS",
            "STORE", "SHOP", "MARKET", "SUPERMARKET", "RESTAURANT", "CAFE",
            "BANK", "SERVICE", "SOLUTIONS"
        )
        
        for (line in lines) {
            val cleanLine = line.trim()
            if (cleanLine.length < 3 || cleanLine.length > 50) continue
            
            // Skip lines that are clearly not merchants (prices, dates, etc.)
            if (isPrice(cleanLine)) continue
            if (isDate(cleanLine)) continue
            if (isReceiptNumber(cleanLine)) continue
            
            // Check if it contains merchant keywords
            val hasKeyword = merchantKeywords.any { keyword ->
                cleanLine.uppercase(Locale.getDefault()).contains(keyword)
            }
            
            // Lines in ALL CAPS are often merchant names
            val isAllCaps = cleanLine == cleanLine.uppercase(Locale.getDefault())
            
            // Score the line
            var score = 0
            if (hasKeyword) score += 2
            if (isAllCaps) score += 1
            if (cleanLine.split(" ").size in 1..4) score += 1 // Reasonable word count
            
            if (score >= 2) {
                candidates.add(cleanMerchantName(cleanLine))
            }
        }
        
        // Also check for lines near the top of receipt (merchants often at top)
        if (candidates.isEmpty() && lines.isNotEmpty()) {
            val firstFewLines = lines.take(5)
            for (line in firstFewLines) {
                val cleanLine = line.trim()
                if (cleanLine.length in 3..50 && !isPrice(cleanLine) && !isDate(cleanLine)) {
                    candidates.add(cleanMerchantName(cleanLine))
                }
            }
        }
        
        return candidates.distinct()
    }
    
    /**
     * Find the best matching merchant from the database.
     */
    private suspend fun findBestMerchantMatch(candidates: List<String>): MerchantMatch? {
        var bestMatch: MerchantMatch? = null
        var highestConfidence = 0.0
        
        // Get all canonical merchants from database
        val knownMerchants = merchantNormalizationDao.getTopMerchants(100)
        
        for (candidate in candidates) {
            val normalizedCandidate = MerchantKeyGenerator.generate(candidate)
            
            for (knownMerchant in knownMerchants) {
                val normalizedKnown = MerchantKeyGenerator.generate(knownMerchant.normalizedName)
                
                // Calculate similarity
                val similarity = StringDistanceUtils.jaroWinklerSimilarity(
                    normalizedCandidate,
                    normalizedKnown
                )
                
                // Also check edit distance for typos
                val editDistance = StringDistanceUtils.levenshteinDistance(
                    normalizedCandidate,
                    normalizedKnown
                )
                
                // Combined score
                val confidence = when {
                    similarity > 0.9 -> 0.95
                    similarity > 0.8 -> 0.85
                    similarity > 0.7 && editDistance <= MAX_EDIT_DISTANCE -> 0.75
                    similarity > 0.6 && editDistance <= 2 -> 0.65
                    else -> 0.0
                }
                
                if (confidence > highestConfidence && confidence >= MIN_CONFIDENCE_THRESHOLD) {
                    highestConfidence = confidence
                    bestMatch = MerchantMatch(
                        canonicalName = knownMerchant.normalizedName,
                        originalName = candidate,
                        confidence = confidence,
                        merchantId = knownMerchant.id
                    )
                }
            }
        }
        
        return bestMatch
    }
    
    /**
     * Verify if an existing merchant name matches OCR text.
     */
    private fun verifyExistingMerchant(
        existingMerchant: String,
        ocrCandidates: List<String>
    ): String? {
        val normalizedExisting = MerchantKeyGenerator.generate(existingMerchant)
        
        for (candidate in ocrCandidates) {
            val normalizedCandidate = MerchantKeyGenerator.generate(candidate)
            
            val similarity = StringDistanceUtils.jaroWinklerSimilarity(
                normalizedExisting,
                normalizedCandidate
            )
            
            if (similarity > 0.8) {
                return existingMerchant // Return the existing name as it's verified
            }
        }
        
        return null
    }
    
    /**
     * Clean up merchant name by removing common artifacts.
     */
    private fun cleanMerchantName(name: String): String {
        return name
            .replace(Regex("\\s+"), " ") // Multiple spaces to single
            .replace(Regex("[^\\w\\s]"), "") // Remove special chars except spaces
            .trim()
            .uppercase(Locale.getDefault())
    }
    
    /**
     * Check if text looks like a price.
     */
    private fun isPrice(text: String): Boolean {
        return text.matches(Regex(".*\\d+[.,]\\d{2}.*")) && // Has decimal
               (text.contains("€") || text.contains("$") || 
                text.contains("EUR") || text.contains("USD"))
    }
    
    /**
     * Check if text looks like a date.
     */
    private fun isDate(text: String): Boolean {
        val datePatterns = listOf(
            Regex("\\d{2}/\\d{2}/\\d{2,4}"), // DD/MM/YY or DD/MM/YYYY
            Regex("\\d{2}-\\d{2}-\\d{2,4}"), // DD-MM-YY or DD-MM-YYYY
            Regex("\\d{4}-\\d{2}-\\d{2}"), // YYYY-MM-DD
            Regex("\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}") // D.M.YY or D.M.YYYY
        )
        return datePatterns.any { pattern -> text.matches(pattern) }
    }
    
    /**
     * Check if text looks like a receipt/transaction number.
     */
    private fun isReceiptNumber(text: String): Boolean {
        return text.uppercase().contains("RECEIPT") ||
               text.uppercase().contains("TRANS") ||
               text.uppercase().contains("REF") ||
               text.matches(Regex("^\\d{4,}$")) // Just a long number
    }
}

/**
 * Result of merchant extraction.
 */
data class MerchantExtractionResult(
    val merchantName: String,
    val confidence: Double,
    val source: String,
    val alternatives: List<String>
)

/**
 * Match result from database.
 */
private data class MerchantMatch(
    val canonicalName: String,
    val originalName: String,
    val confidence: Double,
    val merchantId: Long
)
