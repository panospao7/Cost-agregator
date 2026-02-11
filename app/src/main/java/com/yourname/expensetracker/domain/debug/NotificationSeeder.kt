package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.data.database.entity.RawNotification
import javax.inject.Inject
import kotlin.random.Random

class NotificationSeeder @Inject constructor() {

    val categories = mapOf(
        "Groceries" to listOf("AB Vassilopoulos", "Sklavenitis", "Lidl", "Masoutis", "My Market"),
        "Transport" to listOf("Uber", "Beat", "OASA", "Shell", "EKO", "Aegean Airlines"),
        "Bills" to listOf("DEI", "EYDAP", "Vodafone", "Cosmote", "Wind"),
        "Entertainment" to listOf("Netflix", "Spotify", "Village Cinemas", "Steam", "PlayStation"),
        "Shopping" to listOf("Amazon", "Skroutz", "Zara", "H&M", "Public", "Plaisio"),
        "Dining" to listOf("Goody's", "Wolt", "E-Food", "Starbucks", "Gregorys")
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

    fun generate(count: Int): List<RawNotification> {
        val notifications = mutableListOf<RawNotification>()
        val now = System.currentTimeMillis()
        val twoMonthsMs = 60L * 24 * 60 * 60 * 1000

        for (i in 0 until count) {
            val type = Random.nextInt(100)
            val notification = when {
                type < 5 -> generateSpam(now, twoMonthsMs) // 5% Spam
                type < 10 -> generateUnknown(now, twoMonthsMs) // 5% Unknown
                type < 15 -> generateRecurring(i, now) // 5% Recurring candidates
                else -> generateTransaction(now, twoMonthsMs) // 85% Normal Transactions
            }
            notifications.add(notification)
        }
        return notifications
    }

    private fun generateTransaction(now: Long, rangeMs: Long): RawNotification {
        val categoryEntry = categories.entries.random()
        val merchant = categoryEntry.value.random()
        val amount = Random.nextDouble(5.0, 150.0)
        val date = now - Random.nextLong(rangeMs)
        
        // Randomize source slightly to test normalization
        val sources = listOf("Revolut", "Piraeus", "Eurobank", "Alpha Bank")
        val source = sources.random()

        val text = when (source) {
            "Revolut" -> "Spent €${"%.2f".format(amount)} at $merchant."
            "Piraeus" -> "Agora €${"%.2f".format(amount)} me karta ... sto $merchant"
            else -> "Purchase of €${"%.2f".format(amount)} at $merchant completed."
        }

        return RawNotification(
            packageName = "com.simulation.$source".lowercase(),
            appName = source,
            title = "Transaction Alert",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
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

    private fun generateRecurring(index: Int, now: Long): RawNotification {
        val (merchant, amount) = recurringTemplates.random()
        // Random date within last 60 days
        val date = now - Random.nextLong(60L * 24 * 60 * 60 * 1000)

        return RawNotification(
            packageName = "com.simulation.revolut",
            appName = "Revolut",
            title = "Recurring Payment",
            text = "Spent €$amount at $merchant.",
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }

    private fun generateSpam(now: Long, rangeMs: Long): RawNotification {
        val text = spamTemplates.random()
        val date = now - Random.nextLong(rangeMs)
        return RawNotification(
            packageName = "com.android.mms",
            appName = "Messages",
            title = unknownSources.random(),
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }

    private fun generateUnknown(now: Long, rangeMs: Long): RawNotification {
        val amount = Random.nextDouble(10.0, 50.0)
        val date = now - Random.nextLong(rangeMs)
        val text = "Payment of €${"%.2f".format(amount)} to Unknown Merchant."
        return RawNotification(
            packageName = "com.unknown.app",
            appName = "Unknown App",
            title = "Payment Notification",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
}
