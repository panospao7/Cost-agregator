package com.yourname.expensetracker.domain.categorization

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

class SemanticKeywordMatcher(
    private val greeklishNormalizer: GreeklishNormalizer = GreeklishNormalizer()
) {
    
    private val categoryKeywords = CategoryKeywords.getAllKeywords()
    
    private data class CompiledPattern(
        val regex: Regex,
        val categoryName: String,
        val confidence: Double,
        val pattern: String
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
    
    private val compiledKeywords: Map<String, Map<String, Double>> = categoryKeywords
    
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
        
        categoryKeywords.forEach { (category, keywords) ->
            keywords.forEach { (keyword, weight) ->
                try {
                    val wordBoundaryPattern = Regex("""\b${Regex.escape(keyword)}\b""", RegexOption.IGNORE_CASE)
                    val matchResult = wordBoundaryPattern.find(normalized)
                    
                    if (matchResult != null) {
                        val isAtStart = matchResult.range.first == 0
                        val positionBoost = if (isAtStart) 0.10 else 0.0
                        
                        val finalWeight = minOf(weight + positionBoost, 0.98)
                        
                        val matches = scores.getOrPut(category) { mutableListOf() }
                        matches.add(SemanticMatch(
                            categoryName = category,
                            confidence = finalWeight,
                            matchedKeyword = keyword,
                            matchType = "keyword"
                        ))
                    }
                } catch (e: Exception) {
                    // Skip invalid keywords
                }
            }
        }
        
        return scores.flatMap { (_, matches) ->
            matches
        }.filter { it.confidence >= minConfidence }
            .groupBy { it.categoryName }
            .map { (category, categoryMatches) ->
                val bestMatch = categoryMatches.maxByOrNull { it.confidence }!!
                bestMatch
            }
            .sortedByDescending { it.confidence }
    }
    
    fun findBestMatch(merchant: String, minConfidence: Double = 0.50): SemanticMatch? {
        val matches = match(merchant, minConfidence)
        return matches.firstOrNull()
    }
    
    fun hasKeywordMatch(merchant: String, category: String): Boolean {
        val matches = match(merchant, 0.30)
        return matches.any { it.categoryName == category }
    }
    
    fun getTopKeywords(merchant: String, limit: Int = 5): List<SemanticMatch> {
        return match(merchant, 0.30).take(limit)
    }
}
