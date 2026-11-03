package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.util.AppConstants
import java.util.regex.Pattern

/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
import com.yourname.expensetracker.domain.util.AmountUtils
import com.yourname.expensetracker.domain.util.CommonPatterns
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GenericTransactionParser @Inject constructor(
    private val currencyNormalizer: CurrencyNormalizer,
    private val merchantCleaner: MerchantCleaner,
    private val directionDetector: TransferDirectionDetector,  // NEW: Direction detection
    private val timeProvider: TimeProvider
) {

    // Strong signals that this is a REAL transaction notification
    private val strongTransactionSignals by lazy {
        listOf(
            // English patterns that strongly indicate actual transactions
            Pattern.compile("""(?:you\s+)?paid\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""payment\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""charged?\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:debit|deducted)\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""transaction\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            // Greek patterns - using UNICODE_CASE and \p{L} for Greek letters
            Pattern.compile("""(?:πληρω|χρεω|αγορ[αά])[\p{L}]*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:στ[οη]|at)\s""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            // Greeklish patterns
            Pattern.compile("""(?:pliromi|xreosi|hreosi|agora)\w*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:sto|se|stin?)\s""", Pattern.CASE_INSENSITIVE),
        )
    }

    // Deposit/Income signals - HIGH PRIORITY (checked first)
    private val depositSignals by lazy {
        listOf(
            // English deposit patterns
            Pattern.compile("""(?:deposit|credited|refund(?:ed)?|top(?:-|\s)?up|added\s+money)[\p{L}\s]*[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            // Greek deposit patterns
            Pattern.compile("""(?:κατάθεση|πίστωση|μισθοδοσία|επιστροφή)[\p{L}]*\s*[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
            // Salary patterns
            Pattern.compile("""(?:salary|wages|μισθ[όό]ς)[\p{L}]*\s*[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        )
    }

    private val transferSignals by lazy {
        listOf(
            Pattern.compile("""transfer\s+(?:received|from|to|sent|in|out|inbound|outbound)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:incoming|outgoing)\s+transfer""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""received\s+[€$£]?\s*\d[\d.,]*.*\bfrom\b""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""sent\s+[€$£]?\s*\d[\d.,]*.*\bto\b""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""transferred\s+[€$£]?\s*\d[\d.,]*.*\b(?:to|from)\b""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:έμβασμα|εμβασμα|μεταφορ[αά])""", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
        )
    }

    // NEGATIVE signals — if present, this is NOT a transaction
    // Using Regex to enforce word boundaries for English words to avoid "Coffee" matching "offer"
    private val negativeSignalsPattern by lazy {
        Pattern.compile(
            """\b(offer|discount|save\s+up\s+to|earn|free|up\s+to|starting\s+from|otp|verification|code|unsubscribe|opt\s+out|sale|%\s+off|promo|your\s+order|tracking|shipped|delivered|reminder|rate\s+us|review|survey)\b|""" +
            """(προσφορά|έκπτωση|εξοικονομ|κέρδισε|δωρεάν|έως|κωδικός|υπενθύμιση)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    private val balanceStripPattern by lazy {
        Pattern.compile(
            """(?m)^\s*(?:(?:avail(?:able)?|διαθέσιμο|διαθεσιμο|diathesimo)\s+)?(?:υπόλοιπ[οό]|υπολοιπο|διαθέσιμο|διαθεσιμο|ypoloipo|ipolipo|diathesimo|balance|avail(?:able)?|remaining|limit)\s*:?\s*.*$""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    private fun stripBalanceLines(text: String): String {
        return balanceStripPattern.matcher(text).replaceAll("").trim()
    }

    private val amountPattern by lazy {
        CommonPatterns.AMOUNT_REGEX
    }

    private val balanceContextRegex = Regex(
        """(?<![\p{L}\p{N}])(balance|available|avail|remaining|limit|υπόλοιπ[οό]|υπολοιπο|διαθέσιμ[οό]|διαθεσιμο|ypoloipo|ipolipo|diathesimo)(?![\p{L}\p{N}])"""
    )

    private fun extractClauseBoundedContext(text: String, matchStart: Int, matchEnd: Int): String {
        val prefixStart = (matchStart - 28).coerceAtLeast(0)
        val prefix = text.substring(prefixStart, matchStart).lowercase()
        val lastClause = prefix.substringAfterLast('.').substringAfterLast(';').substringAfterLast(',')
            .substringAfterLast('\n')

        val suffixEnd = (matchEnd + 28).coerceAtMost(text.length)
        val suffix = text.substring(matchEnd, suffixEnd).lowercase()
        val nextClause = suffix.substringBefore('.').substringBefore(';').substringBefore(',')
            .substringBefore('\n')

        return "$lastClause $nextClause"
    }

    private data class CurrencyTaggedCandidate(
        val amount: Double,
        val currency: String,
        val isBalanceContext: Boolean
    )

    private fun extractCurrencyTaggedCandidates(text: String): List<CurrencyTaggedCandidate> {
        val candidates = mutableListOf<CurrencyTaggedCandidate>()
        val matcher = CommonPatterns.MONEY_AMOUNT_WITH_CURRENCY.matcher(text)
        while (matcher.find()) {
            val token = matcher.group(1) ?: matcher.group(2) ?: continue
            val parsed = AmountUtils.parseAmount(token)
            if (parsed != null && parsed >= AppConstants.Parser.MIN_VALID_AMOUNT && parsed <= AppConstants.Parser.MAX_GENERIC_PARSER_AMOUNT) {
                val matchedSpan = matcher.group(0) ?: continue
                val currencyRaw = matchedSpan.replace(token, "").trim()
                val normalizedCurrency = if (
                    currencyRaw.equals("ΕΥΡΩ", ignoreCase = true) ||
                    currencyRaw.equals("ευρώ", ignoreCase = true)
                ) {
                    "EUR"
                } else {
                    currencyNormalizer.normalize(currencyRaw)
                }
                val context = extractClauseBoundedContext(text, matcher.start(), matcher.end())
                candidates.add(
                    CurrencyTaggedCandidate(
                        amount = parsed,
                        currency = normalizedCurrency,
                        isBalanceContext = balanceContextRegex.containsMatchIn(context)
                    )
                )
            }
        }
        return candidates
    }

    private fun hasMultipleMoneyCandidates(text: String): Boolean {
        val candidates = extractCurrencyTaggedCandidates(text)
        val hasBalance = candidates.any { it.isBalanceContext }
        val hasNonBalance = candidates.any { !it.isBalanceContext }
        return candidates.size > 1 || (hasBalance && hasNonBalance)
    }

    private val MERCHANT_PREFIXES = listOf(" at ", " to ", " σε ", " στον ", " στην ", " στο ", " για ", " sto ", " ston ", " stin ", " se ")

    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fields = listOfNotNull(title, text, bigText)
        val allCandidates = fields.flatMap { extractCurrencyTaggedCandidates(it) }
        val isAmbiguous = fields.any { hasMultipleMoneyCandidates(it) } ||
            allCandidates.map { it.amount to it.currency }.distinct().size > 1 ||
            (allCandidates.any { it.isBalanceContext } && allCandidates.any { !it.isBalanceContext })
        val rawFullText = fields.joinToString(" ")
        val fullText = stripBalanceLines(rawFullText)
        val lowerFull = fullText.lowercase()

        if (negativeSignalsPattern.matcher(lowerFull).find()) return null

        // 2. Check for transfer/deposit signals FIRST (high priority)
        val hasTransferSignal = transferSignals.any { it.matcher(lowerFull).find() }
        val hasDepositSignal = depositSignals.any { it.matcher(lowerFull).find() }

        // 3. If not transfer/deposit, require at least one STRONG transaction signal
        if (!hasTransferSignal && !hasDepositSignal) {
            val hasStrongSignal = strongTransactionSignals.any { it.matcher(lowerFull).find() }
            if (!hasStrongSignal) return null
        }

        // 4. Extract amount
        val amountResult = extractAmountWithAmbiguity(fullText, isAmbiguous) ?: return null

        // 5. Sanity check amount
        if (amountResult.first < AppConstants.Parser.MIN_VALID_AMOUNT || amountResult.first > AppConstants.Parser.MAX_GENERIC_PARSER_AMOUNT) return null

        // 6. Extract merchant
        val merchant = extractMerchant(fullText, title)

        // 7. Determine transaction type
        val transactionType = when {
            hasTransferSignal -> ParsedTransactionType.TRANSFER
            hasDepositSignal -> ParsedTransactionType.DEPOSIT
            else -> ParsedTransactionType.PURCHASE
        }
        
        // 8. Detect transfer direction for deposits/transfers
        val direction = directionDetector.detectDirection(title, text, bigText, transactionType)
        val accountName = directionDetector.extractAccountName(title, text, bigText)

        // 9. Try to parse date from text (international formats - LOW bug fix)
        val parsedDate = tryParseDateFromText(fullText)

        return ParsedTransaction(
            amount = amountResult.first,
            currency = amountResult.second,
            merchant = merchant,
            type = transactionType,
            confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.ML_PREDICTION, // LOGIC-004
            date = parsedDate,
            transferDirection = direction,
            transferAccountName = accountName?.let { 
                when (direction) {
                    ParsedTransferDirection.INCOMING -> "From: $it"
                    ParsedTransferDirection.OUTGOING -> "To: $it"
                    else -> null
                }
            },
            isAmountAmbiguous = amountResult.third
        )
    }

    private fun extractAmountWithAmbiguity(text: String, rawAmbiguous: Boolean): Triple<Double, String, Boolean>? {
        val matcher = amountPattern.matcher(text)
        data class AmountCandidate(
            val amount: Double,
            val currency: String,
            val score: Int,
            val hasPurchaseContext: Boolean,
            val startIndex: Int
        )
        val candidates = mutableListOf<AmountCandidate>()
        while (matcher.find()) {
            val matchStart = matcher.start()
            val matchEnd = matcher.end()
            if (matchStart > 0 && text[matchStart - 1] in listOf('/', '-', ':', '*', '.')) continue
            if (matchEnd < text.length && (text[matchEnd] in listOf('/', '-', ':', '*') || (text[matchEnd] == '.' && matchEnd + 1 < text.length && text[matchEnd + 1].isDigit()))) continue
            val amountStr = matcher.group(2) ?: continue
            val amount = AmountUtils.parseAmount(amountStr) ?: continue
            val leadingCurrency = matcher.group(1)
            val trailingCurrency = matcher.group(3)
            val hasCurrency = !leadingCurrency.isNullOrBlank() || !trailingCurrency.isNullOrBlank()
            val hasSeparator = amountStr.contains(",") || amountStr.contains(".")
            if (!hasCurrency && !hasSeparator) continue

            val rawCurrency = leadingCurrency ?: trailingCurrency ?: "€"
            var score = 0
            if (hasCurrency) score += 3
            if (hasSeparator) score += 2

            val context = extractClauseBoundedContext(text, matchStart, matchEnd)
            val hasPurchaseContext = Regex("""\b(paid|payment|charged|debit|purchase|transaction|καταθεση|κατάθεση|πληρωμ|χρεωσ|χρέωσ|αγορ|μεταφορ|pliromi|xreosi|hreosi|agora)""").containsMatchIn(context)
            if (hasPurchaseContext) {
                score += 2
            }
            if (balanceContextRegex.containsMatchIn(context)) {
                score -= 2
            }

            candidates.add(
                AmountCandidate(
                    amount = amount,
                    currency = currencyNormalizer.normalize(rawCurrency),
                    score = score,
                    hasPurchaseContext = hasPurchaseContext,
                    startIndex = matcher.start()
                )
            )
        }
        if (candidates.isEmpty()) return null
        val best = candidates.maxWithOrNull(
            compareBy<AmountCandidate> { it.score }
                .thenBy { it.hasPurchaseContext }
                .thenBy { -it.startIndex }
        ) ?: return null
        return Triple(best.amount, best.currency, rawAmbiguous)
    }

    private fun extractMerchant(text: String, title: String?): String {
        val normalized = text.replace('\u00A0', ' ')
        for (prefix in MERCHANT_PREFIXES) {
            val index = normalized.indexOf(prefix, ignoreCase = true)
            if (index != -1) {
                val after = normalized.substring(index + prefix.length).trim()
                return merchantCleaner.clean(after)
            }
        }
        // Fallback to title if it's not a generic keyword
        if (!title.isNullOrBlank() && !isGenericTitle(title.lowercase())) {
            return merchantCleaner.clean(title)
        }
        return "Unknown"
    }

    private fun isGenericTitle(title: String): Boolean {
        val genericWords = listOf("payment", "purchase", "transaction", "alert", "notification",
            "πληρωμή", "αγορά", "συναλλαγή", "ειδοποίηση")
        return genericWords.any { title.contains(it) }
    }

    /**
     * Tries to parse a date from notification text (international formats).
     * Returns null if no valid date found.
     */
    private fun tryParseDateFromText(text: String): Long? {
        val patterns = listOf(
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})"""),   // dd/MM/yyyy, dd.MM.yyyy, dd-MM-yyyy
            Regex("""(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})"""),   // yyyy-MM-dd (ISO)
            Regex("""(\d{1,2})[/.-](\d{1,2})[/.-](\d{2})"""),   // dd/MM/yy
            Regex("""(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+(\d{4})""", RegexOption.IGNORE_CASE)
        )
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        for (regex in patterns) {
            val match = regex.find(text) ?: continue
            val groups = match.groupValues
            if (groups.size < 4) continue
            try {
                val parsedDate = when {
                    groups[1].length == 4 -> parseStrictLocalDate(
                        dateText = "%04d-%02d-%02d".format(
                            groups[1].toInt(),
                            groups[2].toInt(),
                            groups[3].toInt()
                        ),
                        formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
                    )

                    groups[3].length == 4 -> parseStrictLocalDate(
                        dateText = "%02d/%02d/%04d".format(
                            groups[1].toInt(),
                            groups[2].toInt(),
                            groups[3].toInt()
                        ),
                        formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
                    )

                    groups[3].length == 2 -> {
                        val year = groups[3].toInt()
                        parseStrictLocalDate(
                            dateText = "%02d/%02d/%04d".format(
                                groups[1].toInt(),
                                groups[2].toInt(),
                                if (year < 50) 2000 + year else 1900 + year
                            ),
                            formatter = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
                        )
                    }

                    groups[2].matches(Regex("""[A-Za-z]+""")) -> {
                        val monthToken = groups[2].take(3).lowercase(Locale.ENGLISH)
                        if (monthToken !in months) {
                            null
                        } else {
                            parseStrictLocalDate(
                                dateText = "%02d %s %04d".format(
                                    groups[1].toInt(),
                                    monthToken.replaceFirstChar { it.titlecase(Locale.ENGLISH) },
                                    groups[3].toInt()
                                ),
                                formatter = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH)
                                    .withResolverStyle(ResolverStyle.STRICT)
                            )
                        }
                    }

                    else -> null
                }

                val ts = parsedDate
                    ?.atTime(12, 0)
                    ?.atZone(ZoneId.systemDefault())
                    ?.toInstant()
                    ?.toEpochMilli()
                    ?: continue

                val now = timeProvider.now()
                if (ts in 1..now + 86_400_000) return ts
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse date from pattern")
            }
        }
        return null
    }

    private fun parseStrictLocalDate(
        dateText: String,
        formatter: DateTimeFormatter
    ): LocalDate? {
        return try {
            LocalDate.parse(dateText, formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
