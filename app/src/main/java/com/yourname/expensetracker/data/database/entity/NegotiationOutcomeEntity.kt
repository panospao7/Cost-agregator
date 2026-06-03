package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "negotiation_outcomes",
    indices = [
        Index(value = ["subscriptionId"]),
        Index(value = ["createdAt"]),
        Index(value = ["outcome"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ManualRecurringExpense::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class NegotiationOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val outcome: String,
    val oldAmount: Double,
    val newAmount: Double?,
    val currency: String,
    val savingsAmount: Double?,
    val notes: String?,
    val marketRateSource: String?,
    val createdAt: Long
)
