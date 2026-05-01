package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "spending_challenges",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isActive"]),
        Index(value = ["endDate"]),
        Index(value = ["isActive", "endDate"])
    ]
)
data class SpendingChallengeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val type: String,
    val startDate: Long,
    val endDate: Long,
    val targetAmount: Double? = null,
    @ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR",
    val categoryId: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val isActive: Boolean = true,
    val baselineAmount: Double? = null,
    val baselineStartDate: Long? = null,
    val baselineEndDate: Long? = null,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L
)
