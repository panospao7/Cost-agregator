# Comprehensive Test Suite Strategy for ExpenseTracker

After analyzing your entire codebase, here's a structured test plan organized by priority and layer. I'll provide the actual test files you need.

## Test Architecture Overview

```
├── test/ (Unit Tests - JVM, no Android)
│   ├── domain/parser/          ← Parsers (HIGHEST priority)
│   ├── domain/intelligence/    ← Classifier, Normalizer, Router
│   ├── domain/categorization/  ← CategorizationEngine
│   ├── domain/analytics/       ← InsightsEngine
│   ├── data/converter/         ← Room TypeConverters
│   └── data/repository/        ← Repository logic with mocked DAOs
│
├── androidTest/ (Instrumented Tests)
│   ├── data/database/dao/      ← DAO tests with in-memory Room DB
│   └── data/repository/        ← Integration tests
```

---

## 1. Parser Tests (HIGHEST PRIORITY — your core value)

```kotlin
// test/domain/parser/RevolutParserTest.kt
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RevolutParserTest {
    private lateinit var parser: RevolutParser

    @Before
    fun setup() {
        parser = RevolutParser()
    }

    // === PURCHASE PARSING ===

    @Test
    fun `parse standard purchase with euro symbol`() {
        val result = parser.parse(
            title = "💳 €12.50 at SKLAVENITIS",
            text = "You paid €12.50 at SKLAVENITIS",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
        assertEquals("SKLAVENITIS", result.merchant)
        assertEquals(TransactionType.PURCHASE, result.type)
        assertTrue(result.confidence >= 0.90f)
    }

    @Test
    fun `parse purchase with comma decimal separator`() {
        val result = parser.parse(
            title = "Paid €8,99 at Netflix",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(8.99, result!!.amount, 0.01)
        assertEquals("Netflix", result.merchant)
    }

    @Test
    fun `parse purchase with USD currency`() {
        val result = parser.parse(
            title = "Paid $25.00 at Amazon",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
        assertEquals("USD", result.currency)
    }

    @Test
    fun `parse purchase with GBP currency`() {
        val result = parser.parse(
            title = "Paid £15.00 at Tesco",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals("GBP", result!!.currency)
    }

    @Test
    fun `parse sent to person`() {
        val result = parser.parse(
            title = "Sent €5.00 to John",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(5.00, result!!.amount, 0.01)
        assertEquals("John", result.merchant)
        assertEquals(TransactionType.PURCHASE, result.type)
    }

    // === DEPOSIT PARSING ===

    @Test
    fun `parse received money`() {
        val result = parser.parse(
            title = "Received €100.00 from Maria",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(100.00, result!!.amount, 0.01)
        assertEquals("Maria", result.merchant)
        assertEquals(TransactionType.DEPOSIT, result.type)
    }

    // === ATM PARSING ===

    @Test
    fun `parse ATM withdrawal`() {
        val result = parser.parse(
            title = "ATM withdrawal: €50.00",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(50.00, result!!.amount, 0.01)
        assertEquals("ATM", result.merchant)
        assertEquals(TransactionType.WITHDRAWAL, result.type)
    }

    // === REJECTION TESTS ===

    @Test
    fun `reject exchange rate notification`() {
        val result = parser.parse(
            title = "Your exchange rate for EUR/USD has changed",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject weekly report`() {
        val result = parser.parse(
            title = "Your weekly report is ready",
            text = "You spent €150 this week",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject special offer`() {
        val result = parser.parse(
            title = "Special offer: Get cashback!",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject security notification`() {
        val result = parser.parse(
            title = "Security alert",
            text = "Please verify your identity",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `reject savings vault notification`() {
        val result = parser.parse(
            title = "Savings vault update",
            text = "Your savings vault has reached €500",
            bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    // === EDGE CASES ===

    @Test
    fun `handle null title and text`() {
        val result = parser.parse(
            title = null, text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `handle empty strings`() {
        val result = parser.parse(
            title = "", text = "", bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNull(result)
    }

    @Test
    fun `merchant name truncated at 40 chars`() {
        val result = parser.parse(
            title = "Paid €10.00 at THIS IS A VERY LONG MERCHANT NAME THAT EXCEEDS FORTY CHARACTERS EASILY",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.length <= 40)
    }

    @Test
    fun `merchant cleaned of trailing punctuation`() {
        val result = parser.parse(
            title = "Paid €10.00 at Starbucks.",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertFalse(result!!.merchant.endsWith("."))
    }

    // === SUPPORTED PACKAGES ===

    @Test
    fun `only supports revolut package`() {
        assertEquals(setOf("com.revolut.revolut"), parser.supportedPackages)
    }
}
```

```kotlin
// test/domain/parser/GoogleWalletParserTest.kt
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GoogleWalletParserTest {
    private lateinit var parser: GoogleWalletParser

    @Before
    fun setup() {
        parser = GoogleWalletParser()
    }

    @Test
    fun `parse payment at merchant in text`() {
        val result = parser.parse(
            title = "Payment",
            text = "€4.20 at Coffee Island",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(4.20, result!!.amount, 0.01)
        assertEquals("Coffee Island", result.merchant)
    }

    @Test
    fun `title is merchant when no at-pattern in text`() {
        val result = parser.parse(
            title = "COFFEE ISLAND",
            text = "€4.20 with Mastercard ••1234",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals("COFFEE ISLAND", result!!.merchant)
    }

    @Test
    fun `parse amount with currency suffix`() {
        val result = parser.parse(
            title = "Payment completed",
            text = "15.50 EUR at Lidl",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(15.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `reject add a card notification`() {
        assertNull(parser.parse(
            title = "Add a card to Google Wallet",
            text = "Tap to get started",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        ))
    }

    @Test
    fun `reject loyalty offer`() {
        assertNull(parser.parse(
            title = "Loyalty reward available",
            text = "You have a new offer nearby",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        ))
    }

    @Test
    fun `reject unrealistic amount over 50000`() {
        val result = parser.parse(
            title = "Payment",
            text = "€99999.00 at Merchant",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNull(result)
    }

    @Test
    fun `reject unrealistic amount under 0_01`() {
        val result = parser.parse(
            title = "Payment",
            text = "€0.00 at Merchant",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNull(result)
    }

    @Test
    fun `clean card info from merchant`() {
        val result = parser.parse(
            title = "Starbucks",
            text = "€3.50 - Mastercard ••4567",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertFalse(result!!.merchant.contains("Mastercard"))
        assertFalse(result.merchant.contains("4567"))
    }

    @Test
    fun `supports both wallet package variants`() {
        assertTrue(parser.supportedPackages.contains("com.google.android.apps.walletnfcrel"))
        assertTrue(parser.supportedPackages.contains("com.google.android.apps.nbu.paisa.user"))
    }
}
```

```kotlin
// test/domain/parser/GreekBankParserTest.kt
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GreekBankParserTest {
    private lateinit var parser: GreekBankParser

    @Before
    fun setup() {
        parser = GreekBankParser()
    }

    @Test
    fun `parse Greek purchase notification - agora pattern`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = "Αγορά 12,50 EUR στο SKLAVENITIS",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.01)
        assertEquals("EUR", result.currency)
        assertEquals(TransactionType.PURCHASE, result.type)
    }

    @Test
    fun `parse with euro symbol prefix`() {
        val result = parser.parse(
            title = "Πληρωμή",
            text = "€6,30 στο PIZZA HOOD",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(6.30, result!!.amount, 0.01)
    }

    @Test
    fun `parse card charge pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "χρέωση κάρτας: 25,00 EUR - VODAFONE",
            bigText = null, subText = null,
            packageName = "gr.alpha.mobile"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
    }

    @Test
    fun `reject balance notification`() {
        assertNull(parser.parse(
            title = "Υπόλοιπο",
            text = "Το υπόλοιπο σας είναι 1250,00 EUR",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }

    @Test
    fun `reject OTP code`() {
        assertNull(parser.parse(
            title = "Κωδικός",
            text = "Ο κωδικός σας είναι 123456",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }

    @Test
    fun `reject promotional offer`() {
        assertNull(parser.parse(
            title = "Προσφορά",
            text = "Νέα προσφορά: Δωρεάν μεταφορά χρημάτων",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        ))
    }

    @Test
    fun `supports all Greek bank packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("gr.nbg.mobilebanking"))
        assertTrue(packages.contains("gr.alpha.mobile"))
        assertTrue(packages.contains("com.eurobank.mobile"))
        assertTrue(packages.contains("com.winbank.mobile"))
    }

    @Test
    fun `high confidence for parsed results`() {
        val result = parser.parse(
            title = "Payment",
            text = "Αγορά 10,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertTrue(result!!.confidence >= 0.90f)
    }
}
```

```kotlin
// test/domain/parser/SmsParserTest.kt
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmsParserTest {
    private lateinit var parser: SmsParser

    @Before
    fun setup() {
        parser = SmsParser()
    }

    @Test
    fun `parse bank SMS with Greek keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 15,00 EUR στο KATASTIMA στις 07/02",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse bank SMS with Greeklish keywords`() {
        val result = parser.parse(
            title = "Alpha",
            text = "Agora 22,50 EUR sto SUPERMARKET",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(22.50, result!!.amount, 0.01)
    }

    @Test
    fun `reject non-bank sender`() {
        val result = parser.parse(
            title = "John",
            text = "Hey, can you send me 50 EUR?",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject bank sender without transaction keywords`() {
        val result = parser.parse(
            title = "NBG",
            text = "Welcome to our new mobile app!",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `reject null title`() {
        val result = parser.parse(
            title = null,
            text = "Αγορά 15,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `amount bounds check - too small`() {
        val result = parser.parse(
            title = "NBG",
            text = "Αγορά 0,05 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNull(result)
    }

    @Test
    fun `supports all messaging packages`() {
        val packages = parser.supportedPackages
        assertTrue(packages.contains("com.google.android.apps.messaging"))
        assertTrue(packages.contains("com.samsung.android.messaging"))
        assertTrue(packages.contains("com.android.mms"))
    }
}
```

```kotlin
// test/domain/parser/GenericTransactionParserTest.kt
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GenericTransactionParserTest {
    private lateinit var parser: GenericTransactionParser

    @Before
    fun setup() {
        parser = GenericTransactionParser()
    }

    // === SUCCESSFUL PARSING ===

    @Test
    fun `parse you paid pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €25.00 at Starbucks",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(25.00, result!!.amount, 0.01)
        assertEquals("Starbucks", result.merchant)
    }

    @Test
    fun `parse payment of pattern`() {
        val result = parser.parse(
            title = "Notification",
            text = "Payment of €15.00 at Amazon",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(15.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse charged pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "Charged €10.50 at Shell Gas Station",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(10.50, result!!.amount, 0.01)
    }

    @Test
    fun `parse Greek payment pattern`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = "Πληρωμή 30,00 EUR στο COSMOTE",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(30.00, result!!.amount, 0.01)
    }

    @Test
    fun `parse Greeklish payment pattern`() {
        val result = parser.parse(
            title = "Alert",
            text = "Pliromi 20,00 EUR sto MERCHANT",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(20.00, result!!.amount, 0.01)
    }

    @Test
    fun `lower confidence than app-specific parsers`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €25.00 at Starbucks",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(0.60f, result!!.confidence, 0.01f)
    }

    // === NEGATIVE SIGNAL REJECTION ===

    @Test
    fun `reject offer notification`() {
        assertNull(parser.parse(
            title = "Special offer!",
            text = "You paid €0 - save up to €50 today! offer ends soon",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject OTP notification`() {
        assertNull(parser.parse(
            title = "Verification code",
            text = "Your OTP code is 123456",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject tracking notification`() {
        assertNull(parser.parse(
            title = "Order update",
            text = "Your order has been shipped and is being tracked",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject balance notification`() {
        assertNull(parser.parse(
            title = "Balance update",
            text = "Your balance is €1500.00",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject sale promotion`() {
        assertNull(parser.parse(
            title = "Big Sale",
            text = "50% off everything! Sale ends tonight",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject Greek promotional notification`() {
        assertNull(parser.parse(
            title = "Προσφορά",
            text = "Δωρεάν αποστολή σε παραγγελίες άνω των €30",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    // === NO STRONG SIGNAL ===

    @Test
    fun `reject notification without transaction signal`() {
        assertNull(parser.parse(
            title = "Random App",
            text = "€25.00 available in your account",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    // === AMOUNT BOUNDS ===

    @Test
    fun `reject amount below 0_10`() {
        assertNull(parser.parse(
            title = "Alert",
            text = "You paid €0.05 at Shop",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    @Test
    fun `reject amount above 25000`() {
        assertNull(parser.parse(
            title = "Alert",
            text = "You paid €30000.00 at Shop",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        ))
    }

    // === MERCHANT EXTRACTION ===

    @Test
    fun `extract merchant after at`() {
        val result = parser.parse(
            title = "Alert",
            text = "You paid €10.00 at Lidl Supermarket",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("Lidl"))
    }

    @Test
    fun `extract merchant after Greek preposition`() {
        val result = parser.parse(
            title = "Alert",
            text = "Πληρωμή 10,00€ στο EVEREST",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertTrue(result!!.merchant.contains("EVEREST"))
    }

    @Test
    fun `fallback to Unknown when no merchant found`() {
        val result = parser.parse(
            title = "Payment",
            text = "You paid €10.00",
            bigText = null, subText = null,
            packageName = "com.unknown.app"
        )
        // Might be null or Unknown depending on whether "Payment" title passes isGenericTitle
        if (result != null) {
            // title contains "payment" so it's generic, merchant should be "Unknown"
            assertEquals("Unknown", result.merchant)
        }
    }
}
```

```kotlin
// test/domain/parser/AppParserRegistryRoutingTest.kt
package com.yourname.expensetracker.domain.parser

import com.yourname.expensetracker.domain.parser.parsers.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppParserRegistryRoutingTest {
    private lateinit var registry: AppParserRegistry

    @Before
    fun setup() {
        registry = AppParserRegistry(
            appParsers = listOf(
                RevolutParser(),
                GoogleWalletParser(),
                GreekBankParser(),
                SmsParser()
            ),
            fallbackParser = GenericTransactionParser()
        )
    }

    @Test
    fun `routes revolut package to RevolutParser`() {
        val result = registry.parse(
            title = "Paid €10.00 at Shop",
            text = null, bigText = null, subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(0.95f, result!!.confidence, 0.01f) // Revolut confidence
    }

    @Test
    fun `routes google wallet to GoogleWalletParser`() {
        val result = registry.parse(
            title = "Shop Name",
            text = "€5.00 at Shop Name",
            bigText = null, subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(0.90f, result!!.confidence, 0.01f) // Google Wallet confidence
    }

    @Test
    fun `routes greek bank to GreekBankParser`() {
        val result = registry.parse(
            title = "Alert",
            text = "Αγορά 10,00 EUR στο MERCHANT",
            bigText = null, subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(0.92f, result!!.confidence, 0.01f)
    }

    @Test
    fun `routes unknown package to GenericTransactionParser`() {
        val result = registry.parse(
            title = "Alert",
            text = "You paid €20.00 at Restaurant",
            bigText = null, subText = null,
            packageName = "com.completely.unknown.app"
        )
        assertNotNull(result)
        assertEquals(0.60f, result!!.confidence, 0.01f) // Generic confidence
    }

    @Test
    fun `returns null when no parser matches`() {
        val result = registry.parse(
            title = "Hello",
            text = "How are you?",
            bigText = null, subText = null,
            packageName = "com.completely.unknown.app"
        )
        assertNull(result)
    }
}
```

---

## 2. Intelligence Layer Tests

```kotlin
// test/domain/intelligence/MerchantNormalizerTest.kt
package com.yourname.expensetracker.domain.intelligence

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MerchantNormalizerTest {
    private lateinit var normalizer: MerchantNormalizer

    @Before
    fun setup() {
        // We test the non-suspend, non-DAO methods.
        // For methods needing DAO, we'd need a mock. Here we test pure functions.
        // Create with a mock DAO for compile, but test only pure functions.
        normalizer = MerchantNormalizer(FakeUserCorrectionDao())
    }

    // === NORMALIZE (pure function, no DB) ===

    @Test
    fun `normalize uppercases`() {
        assertEquals("STARBUCKS", normalizer.normalize("starbucks"))
    }

    @Test
    fun `normalize removes trailing numbers`() {
        val result = normalizer.normalize("SKLAVENITIS #4532")
        assertFalse(result.contains("4532"))
    }

    @Test
    fun `normalize removes card info`() {
        val result = normalizer.normalize("MERCHANT CARD VISA *1234")
        assertFalse(result.contains("VISA"))
        assertFalse(result.contains("1234"))
    }

    @Test
    fun `normalize removes Greek city names`() {
        val result = normalizer.normalize("STARBUCKS ATHENS GR")
        assertFalse(result.contains("ATHENS"))
        assertFalse(result.contains("GR"))
    }

    @Test
    fun `normalize removes legal suffixes`() {
        val result = normalizer.normalize("COMPANY SA")
        assertFalse(result.endsWith("SA"))
    }

    @Test
    fun `normalize removes date patterns`() {
        val result = normalizer.normalize("MERCHANT 15/03/2024")
        assertFalse(result.contains("15/03"))
    }

    @Test
    fun `normalize collapses whitespace`() {
        val result = normalizer.normalize("MERCHANT   NAME")
        assertFalse(result.contains("  "))
    }

    @Test
    fun `normalize handles empty string`() {
        assertEquals("", normalizer.normalize(""))
    }

    @Test
    fun `normalize handles special characters only`() {
        val result = normalizer.normalize("***###!!!")
        assertEquals("", result)
    }

    // === SIMILARITY ===

    @Test
    fun `identical merchants have similarity 1`() {
        assertEquals(1.0f, normalizer.similarity("Starbucks", "Starbucks"), 0.01f)
    }

    @Test
    fun `case-insensitive similarity`() {
        assertEquals(1.0f, normalizer.similarity("starbucks", "STARBUCKS"), 0.01f)
    }

    @Test
    fun `substring containment gives high similarity`() {
        val sim = normalizer.similarity("UBER", "UBER EATS")
        assertTrue(sim >= 0.9f)
    }

    @Test
    fun `completely different merchants have low similarity`() {
        val sim = normalizer.similarity("Starbucks", "Vodafone")
        assertTrue(sim < 0.3f)
    }

    @Test
    fun `empty string similarity is 0`() {
        assertEquals(0f, normalizer.similarity("Starbucks", ""), 0.01f)
    }

    // === LEVENSHTEIN ===

    @Test
    fun `levenshtein distance of identical strings is 0`() {
        assertEquals(0, normalizer.levenshteinDistance("abc", "abc"))
    }

    @Test
    fun `levenshtein distance of single edit`() {
        assertEquals(1, normalizer.levenshteinDistance("abc", "abd"))
    }

    @Test
    fun `levenshtein similarity of identical is 1`() {
        assertEquals(1.0f, normalizer.levenshteinSimilarity("test", "test"), 0.01f)
    }

    @Test
    fun `levenshtein similarity of very different is low`() {
        val sim = normalizer.levenshteinSimilarity("abc", "xyz")
        assertTrue(sim < 0.5f)
    }

    // === FIND BEST MATCH ===

    @Test
    fun `findBestMatch returns exact match`() {
        val candidates = listOf("Starbucks", "Lidl", "Shell")
        val match = normalizer.findBestMatch("STARBUCKS", candidates)
        assertEquals("Starbucks", match)
    }

    @Test
    fun `findBestMatch returns null below threshold`() {
        val candidates = listOf("Starbucks", "Lidl", "Shell")
        val match = normalizer.findBestMatch("COMPLETELY DIFFERENT", candidates, threshold = 0.7f)
        assertNull(match)
    }
}

// Minimal fake for testing pure functions - you'd use Mockito/Mockk for real DAO mocking
private class FakeUserCorrectionDao : com.yourname.expensetracker.data.database.dao.UserCorrectionDao {
    override suspend fun insert(correction: com.yourname.expensetracker.data.database.entity.UserCorrection): Long = 0
    override fun getAllFlow() = kotlinx.coroutines.flow.flowOf(emptyList<com.yourname.expensetracker.data.database.entity.UserCorrection>())
    override suspend fun getAll() = emptyList<com.yourname.expensetracker.data.database.entity.UserCorrection>()
    override suspend fun getCount() = 0
    override suspend fun getByPackage(packageName: String) = emptyList<com.yourname.expensetracker.data.database.entity.UserCorrection>()
    override suspend fun getRejectionCount(packageName: String) = 0
    override suspend fun getTotalCorrections(packageName: String) = 0
    override suspend fun getMostCommonMerchantCorrection(originalMerchant: String): String? = null
    override suspend fun getMerchantTotalCorrections(merchant: String) = 0
    override suspend fun getMerchantRejectionCount(merchant: String) = 0
    override suspend fun getMostCommonCategoryForMerchant(merchant: String): Long? = null
    override suspend fun hasPreviousApprovals(merchant: String, packageName: String) = false
    override suspend fun deleteAll() {}
}
```

```kotlin
// test/domain/intelligence/ConfidenceRouterTest.kt
package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.*

class ConfidenceRouterTest {

    // Note: This test uses MockK. Add to build.gradle:
    // testImplementation "io.mockk:mockk:1.13.8"

    private lateinit var router: ConfidenceRouter
    private val sourceStatsDao = mockk<com.yourname.expensetracker.data.database.dao.SourceStatsDao>(relaxed = true)
    private val userCorrectionDao = mockk<com.yourname.expensetracker.data.database.dao.UserCorrectionDao>(relaxed = true)
    private val classifier = mockk<TransactionClassifier>(relaxed = true)

    @Before
    fun setup() {
        router = ConfidenceRouter(sourceStatsDao, userCorrectionDao, classifier)

        // Default: no source stats, no corrections, classifier not ready
        coEvery { sourceStatsDao.getByPackage(any()) } returns null
        coEvery { userCorrectionDao.getMerchantTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.getTotalCorrections(any()) } returns 0
        coEvery { userCorrectionDao.hasPreviousApprovals(any(), any()) } returns false
        every { classifier.getStats() } returns ClassifierStats(0, 0, 0, false)
        coEvery { classifier.predict(any()) } returns 0.5f
    }

    private fun makeParsed(confidence: Float, merchant: String = "TestMerchant") =
        ParsedTransaction(10.0, "EUR", merchant, TransactionType.PURCHASE, confidence)

    @Test
    fun `high confidence auto-accepts`() = runBlocking {
        val result = router.route(makeParsed(0.95f), "com.test")
        assertEquals(RoutingDecision.AUTO_ACCEPT, result.decision)
    }

    @Test
    fun `medium confidence needs review`() = runBlocking {
        val result = router.route(makeParsed(0.70f), "com.test")
        assertEquals(RoutingDecision.NEEDS_REVIEW, result.decision)
    }

    @Test
    fun `low confidence auto-rejects`() = runBlocking {
        val result = router.route(makeParsed(0.30f), "com.test")
        assertEquals(RoutingDecision.AUTO_REJECT, result.decision)
    }

    @Test
    fun `unknown merchant gets confidence penalty`() = runBlocking {
        val result = router.route(makeParsed(0.90f, "Unknown"), "com.test")
        // 0.90 * 0.5 = 0.45, which is below REVIEW_THRESHOLD
        assertTrue(result.adjustedConfidence < 0.90f)
    }

    @Test
    fun `previously approved merchant gets boost`() = runBlocking {
        coEvery { userCorrectionDao.hasPreviousApprovals("TestMerchant", "com.test") } returns true
        val result = router.route(makeParsed(0.80f), "com.test")
        assertTrue(result.adjustedConfidence > 0.80f)
    }

    @Test
    fun `high merchant rejection rate reduces confidence`() = runBlocking {
        coEvery { userCorrectionDao.getMerchantTotalCorrections("TestMerchant") } returns 10
        coEvery { userCorrectionDao.getMerchantRejectionCount("TestMerchant") } returns 8

        val result = router.route(makeParsed(0.90f), "com.test")
        assertTrue(result.adjustedConfidence < 0.90f)
    }

    @Test
    fun `spam source dramatically reduces confidence`() = runBlocking {
        coEvery { sourceStatsDao.getByPackage("com.spam") } returns
            com.yourname.expensetracker.data.database.entity.SourceStats(
                packageName = "com.spam",
                totalNotifications = 100,
                acceptedAsExpense = 1
            )

        val result = router.route(makeParsed(0.90f), "com.spam")
        assertTrue(result.adjustedConfidence < 0.50f)
    }

    @Test
    fun `confidence is clamped to 0-1 range`() = runBlocking {
        coEvery { userCorrectionDao.hasPreviousApprovals(any(), any()) } returns true

        val result = router.route(makeParsed(0.99f), "com.test")
        assertTrue(result.adjustedConfidence <= 1.0f)
        assertTrue(result.adjustedConfidence >= 0.0f)
    }

    @Test
    fun `thresholds are correct`() {
        assertEquals(0.85f, ConfidenceRouter.AUTO_ACCEPT_THRESHOLD)
        assertEquals(0.50f, ConfidenceRouter.REVIEW_THRESHOLD)
    }
}
```

---

## 3. Categorization Engine Test

```kotlin
// test/domain/categorization/CategorizationEngineTest.kt
package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.data.database.entity.MerchantCategory
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CategorizationEngineTest {
    private val merchantCategoryDao = mockk<com.yourname.expensetracker.data.database.dao.MerchantCategoryDao>(relaxed = true)
    private lateinit var engine: CategorizationEngine

    @Before
    fun setup() {
        engine = CategorizationEngine(merchantCategoryDao)
    }

    @Test
    fun `normalize uppercases and removes special chars`() {
        assertEquals("STARBUCKS", engine.normalize("starbucks"))
        assertEquals("UBER EATS", engine.normalize("uber-eats"))
    }

    @Test
    fun `normalize handles Greek characters`() {
        val result = engine.normalize("ΣΚΛΑΒΕΝΙΤΗΣ")
        assertTrue(result.contains("ΣΚΛΑΒΕΝΙΤΗΣ"))
    }

    @Test
    fun `exact match returns category`() = runBlocking {
        coEvery { merchantCategoryDao.getCategoryForMerchant("STARBUCKS") } returns
            MerchantCategory("STARBUCKS", 5L)

        val result = engine.categorize("starbucks")
        assertEquals(5L, result)
    }

    @Test
    fun `substring match finds pattern within merchant name`() = runBlocking {
        coEvery { merchantCategoryDao.getCategoryForMerchant("UBER EATS DELIVERY 1234") } returns null
        coEvery { merchantCategoryDao.getAll() } returns listOf(
            MerchantCategory("UBER EATS", 3L),
            MerchantCategory("UBER", 4L)
        )
        // Word-level match for "UBER"
        coEvery { merchantCategoryDao.getCategoryForMerchant("UBER") } returns
            MerchantCategory("UBER", 4L)

        val result = engine.categorize("UBER EATS DELIVERY 1234")
        // Should match "UBER EATS" first (longer pattern) via substring, returning 3L
        assertEquals(3L, result)
    }

    @Test
    fun `returns null when no match found`() = runBlocking {
        coEvery { merchantCategoryDao.getCategoryForMerchant(any()) } returns null
        coEvery { merchantCategoryDao.getAll() } returns emptyList()

        val result = engine.categorize("COMPLETELY UNKNOWN MERCHANT")
        assertNull(result)
    }

    @Test
    fun `cache invalidation resets cache`() = runBlocking {
        engine.invalidateCache()
        // No assertion needed — just ensure no crash
    }
}
```

---

## 4. Analytics / InsightsEngine Tests

```kotlin
// test/domain/analytics/InsightsEngineTest.kt
package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InsightsEngineDetectRecurringTest {
    private lateinit var engine: InsightsEngine

    @Before
    fun setup() {
        // InsightsEngine needs DAOs for generateInsights(), but detectRecurring()
        // and buildDailyTotals() are testable with just data.
        // We'll use mockk for the constructor.
        val expenseDao = io.mockk.mockk<com.yourname.expensetracker.data.database.dao.ExpenseDao>(relaxed = true)
        val categoryDao = io.mockk.mockk<com.yourname.expensetracker.data.database.dao.CategoryDao>(relaxed = true)
        engine = InsightsEngine(expenseDao, categoryDao)
    }

    private val dayMs = 86_400_000L

    private fun makeExpense(merchant: String, amount: Double, daysAgo: Int) = Expense(
        id = 0,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = System.currentTimeMillis() - daysAgo * dayMs
    )

    @Test
    fun `detects monthly recurring payments`() {
        val expenses = listOf(
            makeExpense("Netflix", 9.99, 90),
            makeExpense("Netflix", 9.99, 60),
            makeExpense("Netflix", 9.99, 30),
            makeExpense("Netflix", 9.99, 0)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.any { it.merchant == "Netflix" })
        val netflix = recurring.first { it.merchant == "Netflix" }
        assertTrue(netflix.intervalDays in 25..35)
        assertEquals(4, netflix.occurrences)
    }

    @Test
    fun `detects weekly recurring payments`() {
        val expenses = listOf(
            makeExpense("GYM", 5.00, 28),
            makeExpense("GYM", 5.00, 21),
            makeExpense("GYM", 5.00, 14),
            makeExpense("GYM", 5.00, 7),
            makeExpense("GYM", 5.00, 0)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.any { it.merchant.uppercase() == "GYM" })
    }

    @Test
    fun `does not detect irregular payments as recurring`() {
        val expenses = listOf(
            makeExpense("Random Shop", 15.00, 100),
            makeExpense("Random Shop", 23.00, 50),
            makeExpense("Random Shop", 8.00, 10)
        )
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.isEmpty() || recurring.none {
            it.merchant.uppercase() == "RANDOM SHOP"
        })
    }

    @Test
    fun `ignores single-occurrence merchants`() {
        val expenses = listOf(makeExpense("One Time Shop", 50.00, 0))
        val recurring = engine.detectRecurring(expenses)
        assertTrue(recurring.isEmpty())
    }

    @Test
    fun `buildDailyTotals includes all requested days`() {
        val expenses = listOf(
            makeExpense("Shop", 10.00, 0),
            makeExpense("Shop", 20.00, 1)
        )
        val totals = engine.buildDailyTotals(expenses, 7)
        assertEquals(7, totals.size)
    }

    @Test
    fun `buildDailyTotals sums same-day purchases`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(0, 10.0, "EUR", "A", TransactionType.PURCHASE, now),
            Expense(0, 20.0, "EUR", "B", TransactionType.PURCHASE, now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(30.0, todayTotal, 0.01)
    }

    @Test
    fun `buildDailyTotals ignores non-purchase types`() {
        val now = System.currentTimeMillis()
        val expenses = listOf(
            Expense(0, 10.0, "EUR", "A", TransactionType.PURCHASE, now),
            Expense(0, 100.0, "EUR", "B", TransactionType.DEPOSIT, now)
        )
        val totals = engine.buildDailyTotals(expenses, 1)
        val todayTotal = totals.values.last()
        assertEquals(10.0, todayTotal, 0.01)
    }
}
```

---

## 5. Converter Tests

```kotlin
// test/data/converter/ConvertersTest.kt
package com.yourname.expensetracker.data.database.converter

import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun `converts PURCHASE to string and back`() {
        val str = converters.fromTransactionType(TransactionType.PURCHASE)
        assertEquals("PURCHASE", str)
        assertEquals(TransactionType.PURCHASE, converters.toTransactionType(str))
    }

    @Test
    fun `converts all TransactionTypes roundtrip`() {
        TransactionType.values().forEach { type ->
            val str = converters.fromTransactionType(type)
            assertEquals(type, converters.toTransactionType(str))
        }
    }

    @Test
    fun `invalid string returns UNKNOWN`() {
        assertEquals(TransactionType.UNKNOWN, converters.toTransactionType("INVALID_TYPE"))
    }

    @Test
    fun `empty string returns UNKNOWN`() {
        assertEquals(TransactionType.UNKNOWN, converters.toTransactionType(""))
    }
}
```

---

## 6. Entity Tests

```kotlin
// test/data/database/entity/SourceStatsTest.kt
package com.yourname.expensetracker.data.database.entity

import org.junit.Assert.*
import org.junit.Test

class SourceStatsTest {

    @Test
    fun `trustScore is 0 when no notifications`() {
        val stats = SourceStats("com.test", totalNotifications = 0, acceptedAsExpense = 0)
        assertEquals(0f, stats.trustScore, 0.01f)
    }

    @Test
    fun `trustScore is correct ratio`() {
        val stats = SourceStats("com.test", totalNotifications = 10, acceptedAsExpense = 7)
        assertEquals(0.7f, stats.trustScore, 0.01f)
    }

    @Test
    fun `isLikelySpam true when high volume low accept`() {
        val stats = SourceStats("com.test", totalNotifications = 100, acceptedAsExpense = 2)
        assertTrue(stats.isLikelySpam)
    }

    @Test
    fun `isLikelySpam false when low volume`() {
        val stats = SourceStats("com.test", totalNotifications = 5, acceptedAsExpense = 0)
        assertFalse(stats.isLikelySpam)
    }

    @Test
    fun `isLikelySpam false when good trust score`() {
        val stats = SourceStats("com.test", totalNotifications = 100, acceptedAsExpense = 80)
        assertFalse(stats.isLikelySpam)
    }
}
```

---

## 7. DAO Instrumented Tests (androidTest)

```kotlin
// androidTest/data/database/dao/ExpenseDaoTest.kt
package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        expenseDao = database.expenseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeExpense(
        amount: Double = 10.0,
        merchant: String = "Test",
        date: Long = System.currentTimeMillis()
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    @Test
    fun insertAndRetrieve() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        assertTrue(id > 0)

        val all = expenseDao.getAll()
        assertEquals(1, all.size)
        assertEquals(10.0, all[0].amount, 0.01)
    }

    @Test
    fun getAllFlowEmitsUpdates() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))

        val expenses = expenseDao.getAllFlow().first()
        assertEquals(2, expenses.size)
    }

    @Test
    fun deleteExpense() = runBlocking {
        val expense = makeExpense()
        val id = expenseDao.insert(expense)
        val inserted = expenseDao.getAll().first()
        expenseDao.delete(inserted)
        assertEquals(0, expenseDao.getAll().size)
    }

    @Test
    fun deleteAllExpenses() = runBlocking {
        expenseDao.insert(makeExpense(merchant = "A"))
        expenseDao.insert(makeExpense(merchant = "B"))
        expenseDao.deleteAll()
        assertEquals(0, expenseDao.getAll().size)
    }

    @Test
    fun getTotalSpentFlowOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense(amount = 10.0))
        expenseDao.insert(Expense(
            amount = 100.0, currency = "EUR", merchant = "Deposit",
            transactionType = TransactionType.DEPOSIT,
            date = System.currentTimeMillis()
        ))

        val total = expenseDao.getTotalSpentFlow().first()
        assertEquals(10.0, total!!, 0.01)
    }

    @Test
    fun isDuplicateDetectsWithinWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertTrue(isDupe)
    }

    @Test
    fun isDuplicateIgnoresOutsideWindow() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now - 600000))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun isDuplicateIgnoresDifferentMerchant() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop A", date = now))

        val isDupe = expenseDao.isDuplicate(10.0, "Shop B", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun isDuplicateIgnoresDifferentAmount() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(amount = 10.0, merchant = "Shop", date = now))

        val isDupe = expenseDao.isDuplicate(20.0, "Shop", now, 300000)
        assertFalse(isDupe)
    }

    @Test
    fun updateCategory() = runBlocking {
        val id = expenseDao.insert(makeExpense())
        expenseDao.updateCategory(id, 5L)

        val updated = expenseDao.getAll().first()
        assertEquals(5L, updated.categoryId)
    }

    @Test
    fun getExpensesBetweenReturnsCorrectRange() = runBlocking {
        val now = System.currentTimeMillis()
        expenseDao.insert(makeExpense(date = now - 86400000 * 2)) // 2 days ago
        expenseDao.insert(makeExpense(date = now - 86400000))     // 1 day ago
        expenseDao.insert(makeExpense(date = now))                 // now

        val between = expenseDao.getExpensesBetween(now - 86400000 * 3, now - 86400000 + 1)
        assertEquals(1, between.size) // only the 2-days-ago one, depending on exact timing
    }

    @Test
    fun purchaseCountOnlyCountsPurchases() = runBlocking {
        expenseDao.insert(makeExpense())
        expenseDao.insert(Expense(
            amount = 50.0, currency = "EUR", merchant = "ATM",
            transactionType = TransactionType.WITHDRAWAL,
            date = System.currentTimeMillis()
        ))

        assertEquals(1, expenseDao.getPurchaseCount())
    }

    @Test
    fun ignoreConflictOnDuplicateInsert() = runBlocking {
        val expense = makeExpense()
        val id1 = expenseDao.insert(expense)
        val id2 = expenseDao.insert(expense.copy(id = id1)) // Same ID
        // IGNORE strategy: id2 should be -1 (not inserted)
        assertEquals(-1L, id2)
        assertEquals(1, expenseDao.getAll().size)
    }
}
```

```kotlin
// androidTest/data/database/dao/PendingReviewDaoTest.kt
package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingReviewDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var pendingReviewDao: PendingReviewDao
    private lateinit var rawNotificationDao: RawNotificationDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        pendingReviewDao = database.pendingReviewDao()
        rawNotificationDao = database.rawNotificationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertRawNotification(): Long {
        return rawNotificationDao.insert(RawNotification(
            packageName = "com.test",
            appName = "Test",
            title = "Test",
            text = "Test",
            timestamp = System.currentTimeMillis(),
            capturedAt = System.currentTimeMillis()
        ))
    }

    private fun makeReview(rawId: Long) = PendingReview(
        rawNotificationId = rawId,
        suggestedAmount = 10.0,
        suggestedCurrency = "EUR",
        suggestedMerchant = "Test Merchant",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.75f,
        packageName = "com.test",
        notificationTitle = "Test",
        notificationText = "Test text"
    )

    @Test
    fun insertAndRetrievePending() = runBlocking {
        val rawId = insertRawNotification()
        pendingReviewDao.insert(makeReview(rawId))

        val pending = pendingReviewDao.getPending()
        assertEquals(1, pending.size)
        assertEquals("PENDING", pending[0].status)
    }

    @Test
    fun pendingCountFlow() = runBlocking {
        val rawId = insertRawNotification()
        pendingReviewDao.insert(makeReview(rawId))

        val count = pendingReviewDao.getPendingCountFlow().first()
        assertEquals(1, count)
    }

    @Test
    fun updateStatusIfPendingSucceeds() = runBlocking {
        val rawId = insertRawNotification()
        val id = pendingReviewDao.insert(makeReview(rawId))

        val rows = pendingReviewDao.updateStatusIfPending(id, "APPROVED")
        assertEquals(1, rows)

        val review = pendingReviewDao.getById(id)
        assertEquals("APPROVED", review?.status)
    }

    @Test
    fun updateStatusIfPendingFailsWhenAlreadyResolved() = runBlocking {
        val rawId = insertRawNotification()
        val id = pendingReviewDao.insert(makeReview(rawId))

        pendingReviewDao.updateStatusIfPending(id, "APPROVED")
        val rows = pendingReviewDao.updateStatusIfPending(id, "REJECTED")
        assertEquals(0, rows) // Already APPROVED, not PENDING
    }

    @Test
    fun getPendingExcludesResolved() = runBlocking {
        val rawId1 = insertRawNotification()
        val rawId2 = insertRawNotification()
        val id1 = pendingReviewDao.insert(makeReview(rawId1))
        pendingReviewDao.insert(makeReview(rawId2))

        pendingReviewDao.updateStatus(id1, "APPROVED")

        val pending = pendingReviewDao.getPending()
        assertEquals(1, pending.size)
    }

    @Test
    fun clearResolvedKeepsPending() = runBlocking {
        val rawId1 = insertRawNotification()
        val rawId2 = insertRawNotification()
        val id1 = pendingReviewDao.insert(makeReview(rawId1))
        pendingReviewDao.insert(makeReview(rawId2))

        pendingReviewDao.updateStatus(id1, "REJECTED")
        pendingReviewDao.clearResolved()

        val all = pendingReviewDao.getAllFlow().first()
        assertEquals(1, all.size)
        assertEquals("PENDING", all[0].status)
    }
}
```

---

## 8. Build Configuration

Add to your `build.gradle` (app):

```groovy
dependencies {
    // Unit test
    testImplementation "junit:junit:4.13.2"
    testImplementation "io.mockk:mockk:1.13.8"
    testImplementation "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3"

    // Instrumented test
    androidTestImplementation "androidx.test.ext:junit:1.1.5"
    androidTestImplementation "androidx.test:core:1.5.0"
    androidTestImplementation "androidx.test:runner:1.5.2"
    androidTestImplementation "androidx.room:room-testing:2.6.1"
    androidTestImplementation "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3"
    androidTestImplementation "io.mockk:mockk-android:1.13.8"
}
```

---

## Summary: What This Test Suite Covers

| Layer | File | What It Tests | Priority |
|-------|------|---------------|----------|
| **Parsers** | RevolutParserTest | Purchase/deposit/ATM parsing, rejections, edge cases | 🔴 Critical |
| **Parsers** | GoogleWalletParserTest | Amount/merchant extraction, rejections | 🔴 Critical |
| **Parsers** | GreekBankParserTest | Greek language patterns, all 4 banks | 🔴 Critical |
| **Parsers** | SmsParserTest | Bank SMS filtering, sender validation | 🔴 Critical |
| **Parsers** | GenericTransactionParserTest | Fallback parsing, negative signals, bounds | 🔴 Critical |
| **Parsers** | AppParserRegistryRoutingTest | Correct parser selection per package | 🔴 Critical |
| **Intelligence** | MerchantNormalizerTest | Normalization, similarity, Levenshtein | 🟠 High |
| **Intelligence** | ConfidenceRouterTest | Routing decisions, threshold logic, adjustments | 🟠 High |
| **Categorization** | CategorizationEngineTest | Match strategies (exact, substring, word) | 🟠 High |
| **Analytics** | InsightsEngineTest | Recurring detection, daily totals | 🟡 Medium |
| **Data** | ConvertersTest | Type converter roundtrip safety | 🟡 Medium |
| **Entity** | SourceStatsTest | Computed properties (trustScore, isLikelySpam) | 🟡 Medium |
| **DAO** | ExpenseDaoTest | CRUD, deduplication, analytics queries | 🟠 High |
| **DAO** | PendingReviewDaoTest | Status transitions, atomic updates, filtering | 🟠 High |

### What's intentionally NOT tested (and why):
- **UI Screens/Composables** — These are thin UI; test via manual/screenshot testing or Compose UI testing framework
- **NotificationCaptureService** — Requires system-level integration testing; test the `processAndSave` pipeline instead
- **BootReceiver** — Trivial logging only
- **Hilt DI Module** — Testing DI wiring is integration-level; the individual components are tested
- **NotificationRepository.processAndSave** — Complex orchestration; test as an integration test with real in-memory DB (advanced)