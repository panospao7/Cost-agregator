package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.notification.RawNotificationFingerprint
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import kotlin.random.Random

class NotificationSeeder @Inject constructor(
    private val timeProvider: TimeProvider
) {

    val categories = mapOf(
        "Groceries" to listOf("AB Vassilopoulos", "Sklavenitis", "Lidl", "Masoutis", "My Market"),
        "Transport" to listOf("Uber", "Beat", "OASA", "Shell", "EKO", "Aegean Airlines"),
        "Utilities" to listOf("DEI", "EYDAP", "Vodafone", "Cosmote", "Wind"),
        "Entertainment" to listOf("Netflix", "Spotify", "Village Cinemas", "Steam", "PlayStation"),
        "Shopping" to listOf("Amazon", "Skroutz", "Zara", "H&M", "Public", "Plaisio"),
        "Food" to listOf("Goody's", "Wolt", "E-Food", "Starbucks", "Gregorys")
    )

    private val spamTemplates = listOf(
        "You won 1000 euros! Claim now at link.com",
        "Your OTP code is 123456. Do not share it.",
        "Limited time offer! 50% off on all items.",
        "Missed call from +306912345678",
        "Your package is out for delivery.",
        "Verify your account by clicking here."
    )

    private val unknownSources = listOf(
        "Unknown Sender", "+306900000000", "InfoSMS", "Alert", "Notice"
    )

    /**
     * Generate seed notifications for debugging.
     *
     * @param count Number of notifications to generate.
     * @param currencySymbol Currency symbol to use in generated text (default "€").
     *                       TODO: Replace with real home currency from settings when
     *                       this seeder is integrated with production flows.
     */
    fun generate(count: Int, currencySymbol: String = "€"): List<RawNotification> {
        val notifications = mutableListOf<RawNotification>()
        val now = timeProvider.now()
        val twoMonthsMs = 60L * 24 * 60 * 60 * 1000

        for (i in 0 until count) {
            val type = Random.nextInt(100)
            val notification = when {
                type < 5 -> generateSpam(now, twoMonthsMs) // 5% Spam
                type < 10 -> generateUnknown(now, twoMonthsMs, currencySymbol) // 5% Unknown
                type < 15 -> generateRecurring(i, now, currencySymbol) // 5% Recurring candidates
                type < 20 -> generateDeposit(now, twoMonthsMs, currencySymbol) // 5% Deposits (salary, transfers)
                else -> generateTransaction(now, twoMonthsMs, currencySymbol) // 80% Normal Transactions (PURCHASE)
            }
            notifications.add(notification)
        }
        return notifications
    }

    private val depositTemplates = listOf(
        // Greek deposits
        Pair("Κατάθεση €{amount} από EMPLOYER", "gr.nbg.mobilebanking"),
        Pair("Πίστωση €{amount} μισθός", "gr.nbg.mobilebanking"),
        Pair("Κατάθεση €{amount} από COMPANY", "com.eurobank.mobile"),
        Pair("Μισθοδοσία €{amount}", "gr.alpha.mobile"),
        // English deposits
        Pair("deposit €{amount} from EMPLOYER", "com.revolut"),
        Pair("received €{amount} salary", "com.revolut"),
        Pair("€{amount} credited from TRANSFER", "com.revolut"),
        Pair("incoming transfer €{amount} from JOHN", "com.revolut"),
        // Generic bank
        Pair("Salary €{amount} deposited", "com.revolut"),
        Pair("Refund €{amount} from STORE", "com.revolut")
    )

    private fun generateDeposit(now: Long, rangeMs: Long, currencySymbol: String = "€"): RawNotification {
        val template = depositTemplates.random()
        val amount = Random.nextDouble(200.0, 3000.0) // Deposits are larger
        val date = now - Random.nextLong(rangeMs)
        
        val text = template.first.replace("{amount}", "%.2f".format(amount)).replace("€", currencySymbol)
        val packageName = template.second

        val source = when (packageName) {
            "com.revolut" -> "Revolut"
            "gr.nbg.mobilebanking" -> "NBG"
            "com.eurobank.mobile" -> "Eurobank"
            "gr.alpha.mobile" -> "Alpha Bank"
            else -> "Bank"
        }

        return RawNotification(
            packageName = packageName,
            appName = source,
            title = "Deposit Received",
            text = text,
            timestamp = date,
            capturedAt = timeProvider.now(),
            dedupeFingerprint = RawNotificationFingerprint.compute(
                packageName = packageName,
                title = "Deposit Received",
                text = text,
                bigText = null,
                timestamp = date
            )
        )
    }

    private fun generateTransaction(now: Long, rangeMs: Long, currencySymbol: String = "€"): RawNotification {
        val categoryEntry = categories.entries.random()
        val merchant = categoryEntry.value.random()
        val amount = Random.nextDouble(5.0, 150.0)
        val date = now - Random.nextLong(rangeMs)
        
        // Randomize source slightly to test normalization
        val sources = listOf("Revolut", "Piraeus", "Eurobank", "Alpha Bank")
        val source = sources.random()

        val text = "Spent $currencySymbol%.2f at %s".format(amount, merchant)

        return RawNotification(
            packageName = "com.simulation.$source".lowercase(),
            appName = source,
            title = "Transaction Alert",
            text = text,
            timestamp = date,
            capturedAt = timeProvider.now(),
            dedupeFingerprint = RawNotificationFingerprint.compute(
                packageName = "com.simulation.$source".lowercase(),
                title = "Transaction Alert",
                text = text,
                bigText = null,
                timestamp = date
            )
        )
    }

    private val recurringTemplates = listOf(
        Pair("Netflix", 13.99),
        Pair("Spotify", 7.99),
        Pair("Cosmote", 35.00),
        Pair("DEI", 45.50),
        Pair("iCloud", 2.99),
        Pair("YouTube Premium", 11.99)
    )

    private fun generateRecurring(index: Int, now: Long, currencySymbol: String = "€"): RawNotification {
        val (merchant, amount) = recurringTemplates.random()
        // Random date within last 60 days
        val date = now - Random.nextLong(60L * 24 * 60 * 60 * 1000)

        return RawNotification(
            packageName = "com.simulation.revolut",
            appName = "Revolut",
            title = "Recurring Payment",
            text = "Spent €%.2f at %s".format(amount, merchant),
            timestamp = date,
            capturedAt = timeProvider.now(),
            dedupeFingerprint = RawNotificationFingerprint.compute(
                packageName = "com.simulation.revolut",
                title = "Recurring Payment",
            text = "Spent $currencySymbol%.2f at %s".format(amount, merchant),
                bigText = null,
                timestamp = date
            )
        )
    }

    private fun generateSpam(now: Long, rangeMs: Long): RawNotification {
        val text = spamTemplates.random()
        val date = now - Random.nextLong(rangeMs)
        val spamTitle = unknownSources.random()
        return RawNotification(
            packageName = "com.android.mms",
            appName = "Messages",
            title = spamTitle,
            text = text,
            timestamp = date,
            capturedAt = timeProvider.now(),
            dedupeFingerprint = RawNotificationFingerprint.compute(
                packageName = "com.android.mms",
                title = spamTitle,
                text = text,
                bigText = null,
                timestamp = date
            )
        )
    }

    private fun generateUnknown(now: Long, rangeMs: Long, currencySymbol: String = "€"): RawNotification {
        val amount = Random.nextDouble(10.0, 50.0)
        val date = now - Random.nextLong(rangeMs)
        val unknownText = "Payment of $currencySymbol%.2f from unknown merchant".format(amount)
        return RawNotification(
            packageName = "com.unknown.app",
            appName = "Unknown App",
            title = "Payment",
            text = unknownText,
            timestamp = date,
            capturedAt = timeProvider.now(),
            dedupeFingerprint = RawNotificationFingerprint.compute(
                packageName = "com.unknown.app",
                title = "Payment",
                text = unknownText,
                bigText = null,
                timestamp = date
            )
        )
    }
}
