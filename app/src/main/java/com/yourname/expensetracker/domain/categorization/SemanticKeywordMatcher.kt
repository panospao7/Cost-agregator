// C12-FIXED: findBestMatch() now detects close competing categories.
// If top score gap < COLLISION_THRESHOLD, returns ambiguous result with alternatives.

package com.yourname.expensetracker.domain.categorization

import javax.inject.Inject
import javax.inject.Singleton

data class SemanticMatch(
    val categoryName: String,
    val confidence: Double,
    val matchedKeyword: String,
    val matchType: String = "keyword"
)

data class PatternMatch(
    val categoryName: String,
    val confidence: Double,
    val pattern: String
)

@Singleton
class SemanticKeywordMatcher @Inject constructor(
    private val greeklishNormalizer: GreeklishNormalizer
) {
    
    private val orderedKeywordEntries = CategoryKeywords.getOrderedKeywordEntries()
    
    private data class CompiledPattern(
        val regex: Regex,
        val categoryName: String,
        val confidence: Double,
        val pattern: String
    )

    private data class CompiledKeyword(
        val entry: OrderedKeywordEntry,
        val regex: Regex
    )
    
    private val compiledPatterns: List<CompiledPattern> = listOf(
        // Food Patterns
        CompiledPattern(Regex(".*(espresso|coffee|cappuccino|latte|freddo)\\s+(lovers|makers|addicts|junkies|lab|project|spot|house).*", RegexOption.IGNORE_CASE), "Food", 0.90, ".*(espresso|coffee|cappuccino|latte|freddo)\\s+(lovers|makers|addicts|junkies|lab|project|spot|house).*"),
        CompiledPattern(Regex(".*(lovers|makers|addicts|junkies)\\s+(coffee|espresso|pizza|burger).*", RegexOption.IGNORE_CASE), "Food", 0.90, ".*(lovers|makers|addicts|junkies)\\s+(coffee|espresso|pizza|burger).*"),
        CompiledPattern(Regex(".*(fresh|fresco|farm|farma|green|bio)\\s+(market|agora|grocery|frouta|kreas).*", RegexOption.IGNORE_CASE), "Food", 0.85, ".*(fresh|fresco|farm|farma|green|bio)\\s+(market|agora|grocery|frouta|kreas).*"),
        CompiledPattern(Regex(".*(pizza|burger|sushi|coffee)\\s+(house|spot|hub|yard|corner|room).*", RegexOption.IGNORE_CASE), "Food", 0.85, ".*(pizza|burger|sushi|coffee)\\s+(house|spot|hub|yard|corner|room).*"),
        CompiledPattern(Regex("^pizza\\s+.+", RegexOption.IGNORE_CASE), "Food", 0.80, "^pizza\\s+.+"),
        CompiledPattern(Regex(".*\\s+coffee$", RegexOption.IGNORE_CASE), "Food", 0.80, ".*\\s+coffee$"),
        CompiledPattern(Regex(".*roasters$", RegexOption.IGNORE_CASE), "Food", 0.80, ".*roasters$"),
        
        // Groceries Patterns
        CompiledPattern(Regex(".*(fresh|fresco|farm|farma|green|bio)\\s+(market|agora|shop|store).*", RegexOption.IGNORE_CASE), "Groceries", 0.85, ".*(fresh|fresco|farm|farma|green|bio)\\s+(market|agora|shop|store).*"),
        CompiledPattern(Regex("^(sklavenitis|ab|lidl|masoutis|mymarket)\\s+.+", RegexOption.IGNORE_CASE), "Groceries", 0.80, "^(sklavenitis|ab|lidl|masoutis|mymarket)\\s+.+"),
        
        // Shopping Patterns
        CompiledPattern(Regex(".*(eshop|online|shop)\\s+(fashion|tech|home|beauty|store).*", RegexOption.IGNORE_CASE), "Shopping", 0.85, ".*(eshop|online|shop)\\s+(fashion|tech|home|beauty|store).*"),
        CompiledPattern(Regex(".*(boutique|collection|wear|style)\\s+(fashion|store|shop).*", RegexOption.IGNORE_CASE), "Shopping", 0.80, ".*(boutique|collection|wear|style)\\s+(fashion|store|shop).*"),
        
        // Transport Patterns
        CompiledPattern(Regex(".*(gas|fuel|petrol)\\s+(station|market).*", RegexOption.IGNORE_CASE), "Transport", 0.90, ".*(gas|fuel|petrol)\\s+(station|market).*")
    )

    private val compiledKeywords: List<CompiledKeyword> = orderedKeywordEntries.map { entry ->
        CompiledKeyword(entry, buildKeywordRegex(entry.keyword))
    }
    
    fun match(merchant: String, minConfidence: Double = 0.40): List<SemanticMatch> {
        val normalized = greeklishNormalizer.normalize(merchant).lowercase()
        val scores = mutableMapOf<String, MutableList<SemanticMatch>>()
        
        // Check pre-compiled patterns first (faster)
        compiledPatterns.forEach { pattern ->
            try {
                if (pattern.regex.matches(normalized)) {
                    val matches = scores.getOrPut(pattern.categoryName) { mutableListOf() }
                    matches.add(SemanticMatch(
                        categoryName = pattern.categoryName,
                        confidence = pattern.confidence,
                        matchedKeyword = "pattern: ${pattern.pattern.take(30)}",
                        matchType = "pattern"
                    ))
                }
            } catch (e: Exception) {
                // Skip invalid patterns
            }
        }
        
        compiledKeywords.forEach { compiled ->
            try {
                val matchResult = compiled.regex.find(normalized)

                if (matchResult != null) {
                    val isAtStart = matchResult.range.first == 0
                    val positionBoost = if (isAtStart) 0.10 else 0.0

                    val finalWeight = minOf(compiled.entry.confidence + positionBoost, 0.98)

                    val matches = scores.getOrPut(compiled.entry.categoryName) { mutableListOf() }
                    matches.add(
                        SemanticMatch(
                            categoryName = compiled.entry.categoryName,
                            confidence = finalWeight,
                            matchedKeyword = compiled.entry.keyword,
                            matchType = "keyword"
                        )
                    )
                }
            } catch (e: Exception) {
                // Skip invalid keywords
            }
        }
        
        return scores.flatMap { (_, matches) ->
            matches
        }.filter { it.confidence >= minConfidence }
            .groupBy { it.categoryName }
            .map { (category, categoryMatches) ->
                val bestMatch = categoryMatches.maxWithOrNull(bestMatchComparator)!!
                bestMatch
            }
            .sortedWith(
                compareByDescending<SemanticMatch> { it.confidence }
                    .thenByDescending { it.matchedKeyword.length }
                    .thenByDescending { m -> scores[m.categoryName]?.size ?: 0 }
                    .thenBy { it.categoryName }
            )
    }
    
    data class KeywordMatchResult(
        val bestMatch: SemanticMatch?,
        val alternatives: List<SemanticMatch>,
        val isAmbiguous: Boolean
    )

    fun findBestMatch(merchant: String, minConfidence: Double = 0.50): KeywordMatchResult {
        val matches = match(merchant, minConfidence)
        if (matches.isEmpty()) {
            return KeywordMatchResult(null, emptyList(), false)
        }
        val first = matches.first()
        if (matches.size == 1) {
            return KeywordMatchResult(first, emptyList(), false)
        }
        val second = matches[1]
        val gap = first.confidence - second.confidence
        return if (gap < COLLISION_THRESHOLD) {
            KeywordMatchResult(
                bestMatch = first,
                alternatives = matches.drop(1),
                isAmbiguous = true
            )
        } else {
            KeywordMatchResult(first, emptyList(), false)
        }
    }
    
    fun hasKeywordMatch(merchant: String, category: String): Boolean {
        val matches = match(merchant, 0.30)
        return matches.any { it.categoryName == category }
    }
    
    fun getTopKeywords(merchant: String, limit: Int = 5): List<SemanticMatch> {
        return match(merchant, 0.30).take(limit)
    }

    private fun buildKeywordRegex(keyword: String): Regex {
        val escaped = Regex.escape(keyword)
        val startsWithWord = keyword.firstOrNull()?.isLetterOrDigit() == true || keyword.firstOrNull() == '_'
        val endsWithWord = keyword.lastOrNull()?.isLetterOrDigit() == true || keyword.lastOrNull() == '_'
        val prefix = if (startsWithWord) "(?<![\\p{L}\\p{N}_])" else ""
        val suffix = if (endsWithWord) "(?![\\p{L}\\p{N}_])" else ""
        return Regex("$prefix$escaped$suffix", RegexOption.IGNORE_CASE)
    }

    private companion object {
        // C12-FIXED: When top two scores differ by less than 10%, return ambiguous.
        private const val COLLISION_THRESHOLD = 0.1
        val bestMatchComparator: Comparator<SemanticMatch> =
            compareByDescending<SemanticMatch> { it.confidence }
                .thenByDescending { it.matchedKeyword.length }
                .thenByDescending { if (it.matchType == "keyword") 1 else 0 }
                .thenBy { it.matchedKeyword }
                .thenBy { it.categoryName }
    }
}
