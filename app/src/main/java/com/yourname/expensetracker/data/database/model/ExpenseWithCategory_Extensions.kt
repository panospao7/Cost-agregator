package com.yourname.expensetracker.data.database.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Extension properties for ExpenseWithCategory to provide formatted display values.
 *
 * [formattedTime] returns time-only ("HH:mm") for compact list rows.
 * The member [ExpenseWithCategory.formattedDate] returns "MMM dd, HH:mm".
 *
 * Previously this extension was also named `formattedDate`, which caused Kotlin
 * member-shadows-extension ambiguity (B.4-29 fix). Renamed to [formattedTime]
 * to make intent explicit and avoid shadowing.
 *
 * [formattedAmount] was previously defined here as an extension but Kotlin member
 * properties always shadow extensions, so it was dead code. The canonical definition
 * now lives in [ExpenseWithCategory.formattedAmount] (B.4-10 unification).
 */

val ExpenseWithCategory.formattedTime: String
    get() {
        return try {
            Instant.ofEpochMilli(expense.date)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        } catch (e: Exception) {
            "Unknown"
        }
    }
