package com.yourname.expensetracker.domain.quickadd

import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import java.text.Normalizer
import java.time.DayOfWeek
import java.util.Locale

/**
 * Vocabulary tables of the quick-add parser (spec 4.4, 4.6). Pure data plus the fold function.
 *
 * Every vocabulary form is stored through [fold], so matching is on folded forms only
 * (lowercase with [Locale.ROOT], accents stripped, final sigma folded to sigma). Greeklish forms
 * are listed explicitly; they are not derived through a transliterator.
 *
 * No runtime `java.util.Currency`: the ISO lists below are embedded constants.
 */
internal object QuickAddVocabulary {

    // ---------------------------------------------------------------- folding

    /** F(t) = NFD-strip-combining-marks(lowercase(Locale.ROOT, t)), then final sigma to sigma. */
    fun fold(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (isCombiningMark(ch)) continue
            out.append(if (ch == 'ς') 'σ' else ch)
        }
        return out.toString()
    }

    fun isCombiningMark(ch: Char): Boolean {
        val type = Character.getType(ch)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Whitespace as the tokeniser sees it: any Unicode whitespace or space separator (Zs). */
    fun isSeparator(ch: Char): Boolean =
        Character.isWhitespace(ch) || Character.getType(ch) == Character.SPACE_SEPARATOR.toInt()

    private val greeklish = GreeklishNormalizer()

    /**
     * G(s) for exact category-name matching: strip combining marks of the Greeklish-normalised
     * text, with whitespace collapsed to single spaces.
     */
    fun categoryFold(text: String): String {
        val normalised = greeklish.normalize(text)
        val decomposed = Normalizer.normalize(normalised, Normalizer.Form.NFD)
        val stripped = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (!isCombiningMark(ch)) stripped.append(ch)
        }
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (ch in stripped) {
            if (isSeparator(ch)) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.setLength(0)
                }
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words.joinToString(" ")
    }

    private fun folded(vararg forms: String): Set<String> = forms.map { fold(it) }.toSet()

    private fun foldedSequence(vararg words: String): List<String> = words.map { fold(it) }

    // ------------------------------------------------------------------ dates

    val TODAY: Set<String> = folded("today", "σήμερα", "simera", "shmera")

    val YESTERDAY: Set<String> = folded(
        "yesterday", "yday", "χθες", "χθές", "εχθές", "χτες", "χτές",
        "xthes", "xtes", "htes", "chthes", "ehthes", "exthes", "extes"
    )

    val DAY_BEFORE: Set<String> = folded(
        "προχθές", "προχτές", "proxthes", "proxtes", "prohthes", "prochthes", "prohtes"
    )

    val FUTURE: Set<String> = folded(
        "tomorrow", "αύριο", "avrio", "aurio", "ayrio", "μεθαύριο", "methavrio", "methaurio"
    )

    /** Multi-token phrases, longest first. Each resolves to "two days ago". */
    val DAY_BEFORE_PHRASES: List<List<String>> = listOf(
        foldedSequence("the", "day", "before", "yesterday"),
        foldedSequence("day", "before", "yesterday")
    )

    /** Multi-token phrases that resolve to a future day. */
    val FUTURE_PHRASES: List<List<String>> = listOf(
        foldedSequence("day", "after", "tomorrow")
    )

    private fun weekdayEntries(day: DayOfWeek, vararg forms: String): List<Pair<String, DayOfWeek>> =
        forms.map { fold(it) to day }

    val WEEKDAYS: Map<String, DayOfWeek> = (
        weekdayEntries(
            DayOfWeek.MONDAY,
            "monday", "mon", "Δευτέρα", "Δευτ", "Δευ", "deftera", "deutera", "dheftera"
        ) +
            weekdayEntries(DayOfWeek.TUESDAY, "tuesday", "tue", "tues", "Τρίτη", "Τρι", "triti") +
            weekdayEntries(DayOfWeek.WEDNESDAY, "wednesday", "wed", "Τετάρτη", "Τετ", "tetarti") +
            weekdayEntries(
                DayOfWeek.THURSDAY,
                "thursday", "thu", "thur", "thurs", "Πέμπτη", "Πεμ", "Πεμπ", "pempti", "pebti"
            ) +
            weekdayEntries(
                DayOfWeek.FRIDAY,
                "friday", "fri", "Παρασκευή", "Παρ", "Παρασκ", "paraskevi", "paraskeui"
            ) +
            weekdayEntries(
                DayOfWeek.SATURDAY,
                "saturday", "sat", "Σάββατο", "Σαβ", "savvato", "savato", "sabbato"
            ) +
            weekdayEntries(
                DayOfWeek.SUNDAY,
                "sunday", "sun", "Κυριακή", "Κυρ", "kyriaki", "kiriaki", "kuriaki"
            )
        ).toMap()

    /** Modifier + weekday is an unsupported date expression. */
    val WEEKDAY_MODIFIERS: Set<String> = folded(
        "last", "next", "this", "περασμένη", "περασμένο", "επόμενη", "επόμενο",
        "perasmeni", "perasmeno", "epomeni", "epomeno"
    )

    /** Articles consumed when they immediately precede a date expression. */
    val DATE_ARTICLES: Set<String> = folded("on", "τη", "την", "το", "στις", "stis", "ti", "tin")

    // ---------------------------------------------------------------- payment

    val CARD: Set<String> = folded("card", "κάρτα", "karta")

    val CASH: Set<String> = folded("cash", "μετρητά", "metrita")

    /** Prepositions consumed when they immediately precede a payment keyword. */
    val PAYMENT_PREPOSITIONS: Set<String> = folded("με", "me", "by", "with", "in")

    // --------------------------------------------------------------- quantity

    /** Units that turn a preceding bare 1..3 digit integer into a quantity. */
    val QUANTITY_UNITS: Set<String> = folded(
        "x", 0x00D7.toChar().toString(), "τμχ", "τεμ", "τεμάχιο", "τεμάχια", "pc", "pcs", "piece", "pieces"
    )

    // -------------------------------------------------------------- currencies

    /** The 28 catalog ISO codes (spec V19, incl. the inactive HRK). A test pins this against the app catalog. */
    val CATALOG_CODES: List<String> = listOf(
        "EUR", "USD", "GBP", "JPY", "CHF", "CAD", "AUD", "CNY", "NZD", "MXN", "SGD", "HKD", "KRW", "TRY",
        "RUB", "INR", "BRL", "ZAR", "SEK", "NOK", "DKK", "PLN", "CZK", "HUF", "RON", "BGN", "HRK", "ISK"
    )

    /**
     * Embedded ISO 4217 alphabetic codes (catalog codes included; they are removed below).
     * Includes the fund, metal and testing codes (BOV, CHE, CHW, CLF, COU, MXV, USN, UYI, UYW, XAG,
     * XAU, XBA-XBD, XDR, XPD, XPT, XSU, XTS, XUA, XXX) and the newest national codes (VED, ZWG, XCG, SVC).
     */
    private val ISO_4217_CODES: List<String> = listOf(
        "AED", "AFN", "ALL", "AMD", "ANG", "AOA", "ARS", "AUD", "AWG", "AZN", "BAM", "BBD", "BDT", "BGN",
        "BHD", "BIF", "BMD", "BND", "BOB", "BOV", "BRL", "BSD", "BTN", "BWP", "BYN", "BZD", "CAD", "CDF",
        "CHE", "CHF", "CHW", "CLF", "CLP", "CNY", "COP", "COU", "CRC", "CUP", "CVE", "CZK", "DJF", "DKK",
        "DOP", "DZD", "EGP", "ERN", "ETB", "EUR", "FJD", "FKP", "GBP", "GEL", "GHS", "GIP", "GMD", "GNF",
        "GTQ", "GYD", "HKD", "HNL", "HRK", "HTG", "HUF", "IDR", "ILS", "INR", "IQD", "IRR", "ISK", "JMD",
        "JOD", "JPY", "KES", "KGS", "KHR", "KMF", "KPW", "KRW", "KWD", "KYD", "KZT", "LAK", "LBP", "LKR",
        "LRD", "LSL", "LYD", "MAD", "MDL", "MGA", "MKD", "MMK", "MNT", "MOP", "MRU", "MUR", "MVR", "MWK",
        "MXN", "MXV", "MYR", "MZN", "NAD", "NGN", "NIO", "NOK", "NPR", "NZD", "OMR", "PAB", "PEN", "PGK",
        "PHP", "PKR", "PLN", "PYG", "QAR", "RON", "RSD", "RUB", "RWF", "SAR", "SBD", "SCR", "SDG", "SEK",
        "SGD", "SHP", "SLE", "SOS", "SRD", "SSP", "STN", "SVC", "SYP", "SZL", "THB", "TJS", "TMT", "TND",
        "TOP", "TRY", "TTD", "TWD", "TZS", "UAH", "UGX", "USD", "USN", "UYI", "UYU", "UYW", "UZS", "VED",
        "VES", "VND", "VUV", "WST", "XAF", "XAG", "XAU", "XBA", "XBB", "XBC", "XBD", "XCD", "XCG", "XDR",
        "XOF", "XPD", "XPF", "XPT", "XSU", "XTS", "XUA", "XXX", "YER", "ZAR", "ZMW", "ZWG", "ZWL"
    )

    /**
     * Lowercase ISO codes outside the catalog. FOREIGN only when attached to, or the token
     * immediately before or after, the money candidate (OD-5); elsewhere they are plain text.
     */
    val NON_CATALOG_CODES: Set<String> =
        ISO_4217_CODES.filterNot { it in CATALOG_CODES }.map { it.lowercase(Locale.ROOT) }.toSet()

    /** A marker: the currency codes it may denote and whether it may stand alone as a token. */
    class Marker(val key: String, val codes: Set<String>, val detachable: Boolean)

    private val markers: List<Marker> = buildMarkers()

    private val markersByKey: Map<String, Marker> = markers.associateBy { it.key }

    /** Markers sorted longest key first, for greedy prefix/suffix matching on attached markers. */
    val MARKERS_LONGEST_FIRST: List<Marker> = markers.sortedByDescending { it.key.length }

    /** The marker a whole token denotes when it stands alone, or null. */
    fun detachedMarker(foldedToken: String): Marker? =
        markersByKey[foldedToken]?.takeIf { it.detachable }

    /** Folded keys of the alphabetic catalog symbols (attached-only markers, spec 4.6). */
    private val ALPHABETIC_SYMBOL_KEYS: Set<String> =
        folded("Fr", "R", "kr", "lei", "Ft", "kn", "Kč", "zł", "лв")

    /**
     * The alphabetic catalog symbol a whole token denotes, or null. Such a token is plain text
     * unless it is the token immediately before or after a money candidate (OD-5 adjacency, owner
     * approvals item 58); the parser then treats it as a detached marker.
     */
    fun adjacentAlphabeticSymbol(foldedToken: String): Marker? =
        markersByKey[foldedToken]?.takeIf { it.key in ALPHABETIC_SYMBOL_KEYS }

    private fun buildMarkers(): List<Marker> {
        val out = LinkedHashMap<String, Marker>()

        fun add(form: String, detachable: Boolean, vararg codes: String) {
            val key = fold(form)
            val existing = out[key]
            if (existing == null) {
                out[key] = Marker(key, codes.toSet(), detachable)
            }
        }

        // Catalog ISO codes: detached anywhere, or attached.
        for (code in CATALOG_CODES) add(code, true, code)

        // Non-alphabetic catalog symbols and compounds: detached anywhere, or attached.
        add("€", true, "EUR")
        add("$", true, "USD")
        add("£", true, "GBP")
        add("¥", true, "JPY", "CNY")
        add("₩", true, "KRW")
        add("₺", true, "TRY")
        add("₽", true, "RUB")
        add("₹", true, "INR")
        add("C$", true, "CAD")
        add("A$", true, "AUD")
        add("NZ$", true, "NZD")
        add("MX$", true, "MXN")
        add("S$", true, "SGD")
        add("HK$", true, "HKD")
        add("R$", true, "BRL")

        // Alphabetic catalog symbols: attached only.
        add("Fr", false, "CHF")
        add("R", false, "ZAR")
        add("kr", false, "SEK", "NOK", "DKK", "ISK")
        add("lei", false, "RON")
        add("Ft", false, "HUF")
        add("kn", false, "HRK")
        add("Kč", false, "CZK")
        add("zł", false, "PLN")
        add("лв", false, "BGN")

        // Attached-only single-letter EUR forms.
        add("e", false, "EUR")
        add("ε", false, "EUR")

        // Words (detached or attached). "won", "real" and "rand" are excluded on purpose.
        for (w in listOf("euro", "euros", "ευρώ", "ευρω", "ευρο", "evro", "evrw", "eyro", "eyrw")) {
            add(w, true, "EUR")
        }
        for (w in listOf("dollar", "dollars", "δολάριο", "δολάρια", "dolario", "dolaria")) {
            add(w, true, "USD")
        }
        for (w in listOf("pound", "pounds", "στερλίνα", "στερλίνες", "sterlina")) {
            add(w, true, "GBP")
        }
        for (w in listOf("λίρα", "λίρες", "lira", "liras")) add(w, true, "GBP", "TRY")
        for (w in listOf("yen", "γιεν")) add(w, true, "JPY")
        for (w in listOf("yuan", "renminbi")) add(w, true, "CNY")
        for (w in listOf("franc", "francs", "φράγκο", "φράγκα")) add(w, true, "CHF")
        for (w in listOf("ruble", "rubles", "rouble", "roubles", "ρούβλι", "ρούβλια")) add(w, true, "RUB")
        for (w in listOf("rupee", "rupees", "ρουπία")) add(w, true, "INR")
        for (w in listOf("krona", "kronor", "krone", "kroner", "κορώνα", "κορώνες")) {
            add(w, true, "SEK", "NOK", "DKK", "ISK")
        }
        add("zloty", true, "PLN")
        add("forint", true, "HUF")
        add("leu", true, "RON")
        for (w in listOf("lev", "leva", "λέβα")) add(w, true, "BGN")
        for (w in listOf("peso", "pesos")) add(w, true, "MXN")

        // Non-catalog ISO codes: attached markers only (adjacency is handled by the parser).
        for (code in NON_CATALOG_CODES) {
            val key = fold(code)
            if (!out.containsKey(key)) out[key] = Marker(key, setOf(code.uppercase(Locale.ROOT)), false)
        }
        return out.values.toList()
    }
}
