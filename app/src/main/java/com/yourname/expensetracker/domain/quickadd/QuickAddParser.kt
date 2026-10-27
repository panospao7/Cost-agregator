package com.yourname.expensetracker.domain.quickadd

import com.yourname.expensetracker.domain.quickadd.QuickAddVocabulary as Vocabulary
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

/**
 * Quick-add line parser (C1). A pure function of [QuickAddInput]:
 * no clock, no system zone, no device locale (case folding uses [Locale.ROOT]), no I/O,
 * no logging, no AI. The parse result carries no raw line, tokens, currency or foreign amount.
 *
 * Pipeline (spec 4.1): length gate, NFC, grouping join, whitespace split, punctuation strip,
 * folding, classification, then amount / date / payment / category / merchant derivation.
 *
 * Foreign currency fails closed: the amount is [FlagReason.AMOUNT_FOREIGN_CURRENCY] and the line
 * hint [LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED] is set. Nothing records or converts the currency.
 */
class QuickAddParser @Inject constructor() {

    fun parse(input: QuickAddInput): QuickAddParseResult {
        val line = input.line
        if (line.length > MAX_LINE_LENGTH) return emptyResult(setOf(LineHint.LINE_TOO_LONG))
        if (line.all { Vocabulary.isSeparator(it) }) return emptyResult(emptySet())

        val tokens = tokenize(line)
        val classified = classify(tokens)
        markAdjacentCurrencyWords(tokens)
        markLeadingQuantity(tokens)

        val homeCurrency = input.homeCurrency

        // Fail closed (owner approvals item 58): a marker that is not exactly the home currency is
        // foreign, and so is any text or number token that carries a Unicode currency-symbol code
        // point (Sc) without being a known home marker ("200 THB-sign", "20 US$", "$US 20", "200<sign>").
        val foreign = tokens.any { token ->
            when (token.kind) {
                Kind.MARKER -> QuickAddAmountRules.isForeign(token.markerCodes, homeCurrency)
                Kind.MONEY ->
                    token.attached.any { QuickAddAmountRules.isForeign(it, homeCurrency) } ||
                        containsCurrencySymbol(token.core)
                Kind.TEXT -> containsCurrencySymbol(token.surface)
                else -> false
            }
        }
        val quantity = tokens.any { it.kind == Kind.QUANTITY }
        val amount = QuickAddAmountRules.decide(
            foreign = foreign,
            quantity = quantity,
            candidateCores = tokens.filter { it.kind == Kind.MONEY }.map { it.core }
        )

        val date = QuickAddDateRules.resolve(classified.dates, input.nowEpochMs, input.zone)
        val payment = derivePayment(tokens)

        val textTokens = tokens.filter { it.kind == Kind.TEXT }
        val category = matchCategories(textTokens, input.categories)
        val merchant = deriveMerchant(textTokens, category.matchedIndices)

        val hints = LinkedHashSet<LineHint>()
        if (foreign) hints.add(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED)
        if (classified.timeSeen) hints.add(LineHint.TIME_IGNORED)
        val allAbsent = amount is FieldParse.Absent && date is FieldParse.Absent &&
            merchant is FieldParse.Absent && payment is FieldParse.Absent && category.field is FieldParse.Absent
        if (allAbsent) hints.add(LineHint.NOTHING_RECOGNISED)

        return QuickAddParseResult(
            amount = amount,
            date = date,
            merchant = merchant,
            payment = payment,
            category = category.field,
            lineHints = hints
        )
    }

    // ------------------------------------------------------------------ tokens

    private enum class Kind { TEXT, MONEY, QUANTITY, MARKER, TIME, DATE, PAYMENT, CONSUMED }

    private class Token(val surface: String) {
        val fold: String = Vocabulary.fold(surface)
        var kind: Kind = Kind.TEXT

        /** MONEY: the token with attached markers removed. */
        var core: String = ""

        /** MONEY: the currency sets of attached markers. */
        var attached: List<Set<String>> = emptyList()

        /** MARKER: the currency set of a detached marker. */
        var markerCodes: Set<String> = emptySet()

        /** PAYMENT. */
        var payment: QuickAddPayment? = null
    }

    private class Classified(val dates: List<DateExpr>, val timeSeen: Boolean)

    private class DateMatch(val expr: DateExpr, val length: Int)

    private fun tokenize(line: String): List<Token> {
        val nfc = Normalizer.normalize(line, Normalizer.Form.NFC)
        val joined = GROUPING_JOIN.replace(nfc) { match ->
            match.value.filter { it !in GROUPING_SPACES }
        }
        val tokens = ArrayList<Token>()
        val current = StringBuilder()
        for (ch in joined) {
            if (Vocabulary.isSeparator(ch)) {
                addToken(tokens, current)
            } else {
                current.append(ch)
            }
        }
        addToken(tokens, current)
        return tokens
    }

    // FQN keeps scripts/kotlin_callable_parser.py able to resolve the parameter type (known_good_state active_db_gate pin).
    private fun addToken(tokens: MutableList<Token>, buffer: kotlin.text.StringBuilder) {
        if (buffer.isEmpty()) return
        val stripped = stripPunctuation(buffer.toString())
        buffer.setLength(0)
        if (stripped.isEmpty() || stripped.all { it in DASHES }) return
        tokens.add(Token(stripped))
    }

    /** Strips trailing, then leading, punctuation; a leading '.' or ',' directly before a digit is kept. */
    private fun stripPunctuation(raw: String): String {
        var start = 0
        var end = raw.length
        while (end > start && raw[end - 1] in PUNCTUATION) end--
        while (start < end && raw[start] in PUNCTUATION) {
            val c = raw[start]
            if ((c == '.' || c == ',') && start + 1 < end && raw[start + 1] in '0'..'9') break
            start++
        }
        return raw.substring(start, end)
    }

    /** True when a combining mark follows a non-letter: folding would hide it, so the core is poisoned. */
    private fun hasOrphanMark(surface: String): Boolean {
        for (i in surface.indices) {
            if (Vocabulary.isCombiningMark(surface[i]) && (i == 0 || !surface[i - 1].isLetter())) return true
        }
        return false
    }

    // ----------------------------------------------------------- classification

    private fun classify(tokens: List<Token>): Classified {
        val dates = ArrayList<DateExpr>()
        var timeSeen = false
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]

            val date = matchDate(tokens, i)
            if (date != null) {
                dates.add(date.expr)
                for (k in i until i + date.length) tokens[k].kind = Kind.DATE
                i += date.length
                continue
            }
            if (token.fold in Vocabulary.DATE_ARTICLES && i + 1 < tokens.size && matchDate(tokens, i + 1) != null) {
                token.kind = Kind.CONSUMED
                i++
                continue
            }
            if (token.fold in Vocabulary.PAYMENT_PREPOSITIONS && i + 1 < tokens.size &&
                paymentOf(tokens[i + 1]) != null
            ) {
                token.kind = Kind.CONSUMED
                i++
                continue
            }
            if (TIME.matches(token.surface)) {
                token.kind = Kind.TIME
                timeSeen = true
                i++
                continue
            }
            if (QUANTITY_SINGLE.matches(token.fold)) {
                token.kind = Kind.QUANTITY
                i++
                continue
            }
            if (BARE_SHORT_INTEGER.matches(token.surface) && i + 1 < tokens.size &&
                tokens[i + 1].fold in Vocabulary.QUANTITY_UNITS
            ) {
                token.kind = Kind.QUANTITY
                tokens[i + 1].kind = Kind.QUANTITY
                i += 2
                continue
            }
            val payment = paymentOf(token)
            if (payment != null) {
                token.kind = Kind.PAYMENT
                token.payment = payment
                i++
                continue
            }
            val marker = Vocabulary.detachedMarker(token.fold)
            if (marker != null) {
                token.kind = Kind.MARKER
                token.markerCodes = marker.codes
                i++
                continue
            }
            val numberLike = QuickAddAmountRules.splitNumberLike(token.fold, hasOrphanMark(token.surface))
            if (numberLike != null) {
                token.kind = Kind.MONEY
                token.core = numberLike.core
                token.attached = numberLike.markers
            }
            i++
        }
        return Classified(dates, timeSeen)
    }

    private fun paymentOf(token: Token): QuickAddPayment? = when (token.fold) {
        in Vocabulary.CARD -> QuickAddPayment.CARD
        in Vocabulary.CASH -> QuickAddPayment.CASH
        else -> null
    }

    private fun matchDate(tokens: List<Token>, i: Int): DateMatch? {
        for (phrase in Vocabulary.DAY_BEFORE_PHRASES) {
            if (startsWith(tokens, i, phrase)) return DateMatch(DateExpr.Relative(2), phrase.size)
        }
        for (phrase in Vocabulary.FUTURE_PHRASES) {
            if (startsWith(tokens, i, phrase)) return DateMatch(DateExpr.Future, phrase.size)
        }
        val token = tokens[i]
        if (token.fold in Vocabulary.WEEKDAY_MODIFIERS && i + 1 < tokens.size &&
            Vocabulary.WEEKDAYS.containsKey(tokens[i + 1].fold)
        ) {
            return DateMatch(DateExpr.Unsupported, 2)
        }
        numericDate(token.surface)?.let { return DateMatch(it, 1) }
        val word = token.fold
        val expr: DateExpr? = when {
            word in Vocabulary.TODAY -> DateExpr.Relative(0)
            word in Vocabulary.YESTERDAY -> DateExpr.Relative(1)
            word in Vocabulary.DAY_BEFORE -> DateExpr.Relative(2)
            word in Vocabulary.FUTURE -> DateExpr.Future
            else -> Vocabulary.WEEKDAYS[word]?.let { DateExpr.Weekday(it) }
        }
        return expr?.let { DateMatch(it, 1) }
    }

    private fun startsWith(tokens: List<Token>, i: Int, phrase: List<String>): Boolean {
        if (i + phrase.size > tokens.size) return false
        for (k in phrase.indices) {
            if (tokens[i + k].fold != phrase[k]) return false
        }
        return true
    }

    private fun numericDate(surface: String): DateExpr.Numeric? {
        DATE_SLASH.matchEntire(surface)?.let { m ->
            val year = m.groupValues[3]
            return DateExpr.Numeric(
                day = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                year = if (year.isEmpty()) 0 else year.toInt(),
                yearDigits = year.length
            )
        }
        DATE_DOT_DASH.matchEntire(surface)?.let { m ->
            val year = m.groupValues[4]
            return DateExpr.Numeric(
                day = m.groupValues[1].toInt(),
                month = m.groupValues[3].toInt(),
                year = year.toInt(),
                yearDigits = year.length
            )
        }
        return null
    }

    /** True when [text] contains a code point of Unicode general category Sc (currency symbol). */
    private fun containsCurrencySymbol(text: String): Boolean =
        text.codePoints().anyMatch { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }

    /**
     * OD-5: a non-catalog ISO code, or an alphabetic catalog symbol (Fr R kr lei Ft kn Kc zl lv), is
     * a currency marker when it is the token immediately before or after a money candidate. Attached
     * ones are handled by the marker split. Whether the marker blocks is decided later by the codes:
     * a marker that is exactly the home currency never blocks.
     * An unknown currency-symbol token next to a money candidate is consumed as a never-home marker.
     * This pass runs before the leading-integer rule so "20 thb taxi" is not read as a quantity.
     */
    private fun markAdjacentCurrencyWords(tokens: List<Token>) {
        for (j in tokens.indices) {
            if (tokens[j].kind != Kind.MONEY) continue
            for (k in intArrayOf(j - 1, j + 1)) {
                if (k !in tokens.indices) continue
                val neighbour = tokens[k]
                if (neighbour.kind != Kind.TEXT) continue
                if (neighbour.fold in Vocabulary.NON_CATALOG_CODES) {
                    neighbour.kind = Kind.MARKER
                    neighbour.markerCodes = setOf(neighbour.fold.uppercase(Locale.ROOT))
                    continue
                }
                val symbol = Vocabulary.adjacentAlphabeticSymbol(neighbour.fold)
                if (symbol != null) {
                    neighbour.kind = Kind.MARKER
                    neighbour.markerCodes = symbol.codes
                } else if (containsCurrencySymbol(neighbour.surface)) {
                    // An unknown currency symbol or compound (a baht sign, "US$", "$US"): consumed
                    // here and never home (an empty code set is not the home currency).
                    neighbour.kind = Kind.MARKER
                    neighbour.markerCodes = emptySet()
                }
            }
        }
    }

    /** OD-3: a leading bare integer followed by a word is a quantity ("2 καφέδες", "15 taxi"). */
    private fun markLeadingQuantity(tokens: List<Token>) {
        val first = tokens.firstOrNull() ?: return
        if (first.kind == Kind.MONEY && first.attached.isEmpty() && BARE_INTEGER.matches(first.surface) &&
            tokens.size > 1 && tokens[1].kind == Kind.TEXT
        ) {
            first.kind = Kind.QUANTITY
        }
    }

    // ---------------------------------------------------------- payment, category

    private fun derivePayment(tokens: List<Token>): FieldParse<QuickAddPayment> {
        val methods = tokens.filter { it.kind == Kind.PAYMENT }.mapNotNull { it.payment }.toSet()
        return when {
            methods.isEmpty() -> FieldParse.Absent
            methods.size >= 2 -> FieldParse.Flagged(FlagReason.PAYMENT_CONFLICT)
            else -> FieldParse.Value(methods.single())
        }
    }

    private class CategoryOutcome(val field: FieldParse<CategoryMatch>, val matchedIndices: Set<Int>)

    /** Exact-name match of the picker categories against windows of the text tokens (spec 4.11). */
    private fun matchCategories(textTokens: List<Token>, categories: List<CategoryRef>): CategoryOutcome {
        if (categories.isEmpty() || textTokens.isEmpty()) return CategoryOutcome(FieldParse.Absent, emptySet())

        val matched = LinkedHashMap<Long, MutableSet<Int>>()
        for (category in categories) {
            val key = Vocabulary.categoryFold(category.name)
            if (key.isEmpty()) continue
            val words = key.split(' ').size
            if (words > textTokens.size) continue
            for (start in 0..textTokens.size - words) {
                val window = textTokens.subList(start, start + words).joinToString(" ") { it.surface }
                if (Vocabulary.categoryFold(window) == key) {
                    matched.getOrPut(category.id) { LinkedHashSet() }.addAll(start until start + words)
                }
            }
        }
        return when (matched.size) {
            0 -> CategoryOutcome(FieldParse.Absent, emptySet())
            1 -> {
                val entry = matched.entries.single()
                CategoryOutcome(
                    FieldParse.Value(CategoryMatch(entry.key, CategorySource.EXACT_NAME)),
                    entry.value
                )
            }
            else -> CategoryOutcome(FieldParse.Flagged(FlagReason.CATEGORY_AMBIGUOUS), emptySet())
        }
    }

    /**
     * The merchant is the text tokens in original order and surface form. Category tokens are
     * removed only when the remaining merchant stays non-blank (spec 4.11).
     */
    private fun deriveMerchant(textTokens: List<Token>, categoryIndices: Set<Int>): FieldParse<MerchantText> {
        val remaining = textTokens.filterIndexed { index, _ -> index !in categoryIndices }
        // Keep every text token when removal would leave no letter or digit (a lone emoji or symbol).
        val used = if (remaining.none { hasLetterOrDigit(it.surface) }) textTokens else remaining
        var merchant = used.joinToString(" ") { it.surface }
        if (merchant.length > MAX_MERCHANT_LENGTH) {
            var cut = merchant.substring(0, MAX_MERCHANT_LENGTH)
            if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
            merchant = cut.trim()
        }
        // Spec 8: a merchant needs at least one Unicode letter or digit; emoji/symbol-only text is not one.
        return if (!hasLetterOrDigit(merchant)) FieldParse.Absent else FieldParse.Value(MerchantText(merchant))
    }

    private fun hasLetterOrDigit(text: String): Boolean =
        text.codePoints().anyMatch { Character.isLetterOrDigit(it) }

    private fun emptyResult(hints: Set<LineHint>): QuickAddParseResult = QuickAddParseResult(
        amount = FieldParse.Absent,
        date = FieldParse.Absent,
        merchant = FieldParse.Absent,
        payment = FieldParse.Absent,
        category = FieldParse.Absent,
        lineHints = hints
    )

    companion object {
        /** Lines longer than this are rejected: every field Absent plus [LineHint.LINE_TOO_LONG]. */
        const val MAX_LINE_LENGTH = 200

        private const val MAX_MERCHANT_LENGTH = 100

        /**
         * Punctuation stripped from both ends of a token (spec 4.1 step 5): `, . ; : ! ?`, U+00B7
         * middle dot, U+2026 ellipsis, U+00AB and U+00BB guillemets, `" ' ( ) [ ]`.
         */
        private val PUNCTUATION: String = chars(0x2C, 0x2E, 0x3B, 0x3A, 0x21, 0x3F, 0xB7, 0x2026, 0xAB, 0xBB, 0x22, 0x27, 0x28, 0x29, 0x5B, 0x5D)

        /** Tokens made only of these dash characters are dropped: `-`, U+2013, U+2014, U+2011, U+2212. */
        private val DASHES: String = chars(0x2D, 0x2013, 0x2014, 0x2011, 0x2212)

        /** U+00A0 NBSP, U+202F narrow NBSP, U+2009 thin space. */
        private val GROUPING_SPACES: String = chars(0x00A0, 0x202F, 0x2009)

        /** Builds a string from code points, so no invisible or look-alike character sits in the source. */
        private fun chars(vararg codePoints: Int): String = codePoints.map { it.toChar() }.joinToString("")

        /** NBSP / narrow NBSP / thin space grouping join (spec 4.1 step 3). An ASCII space never joins. */
        private val GROUPING_JOIN =
            Regex("(?<!\\d)\\d{1,3}(?:[\\u00A0\\u202F\\u2009]\\d{3})+(?:[.,]\\d{1,2})?(?!\\d)")

        private val DATE_SLASH = Regex("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$")
        private val DATE_DOT_DASH = Regex("^(\\d{1,2})([.\\-])(\\d{1,2})\\2(\\d{2,4})$")
        private val TIME = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
        private val QUANTITY_SINGLE = Regex("^(\\d{1,3}[x\\u00D7]|[x\\u00D7]\\d{1,3})$")
        private val BARE_SHORT_INTEGER = Regex("^\\d{1,3}$")
        private val BARE_INTEGER = Regex("^\\d+$")
    }
}
