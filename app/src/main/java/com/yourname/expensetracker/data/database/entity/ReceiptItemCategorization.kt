package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores AI-generated categorization for individual receipt items.
 * Each scanned receipt can have multiple items, each with its own category suggestion.
 */
@Entity(
    tableName = "receipt_item_categorizations",
    foreignKeys = [
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["receiptId"]),
        Index(value = ["expenseId"]),
        Index(value = ["suggestedCategoryId"]),
        Index(value = ["userCorrectedCategoryId"])
    ]
)
data class ReceiptItemCategorization(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val expenseId: Long? = null,
    val itemDescription: String,
    val itemAmount: Double,
    val suggestedCategoryId: Long?,
    val suggestedCategoryName: String?,
    val confidence: Float,
    val aiRationale: String?,
    val alternativeCategoriesJson: String?, // JSON array of {id, name, confidence}
    val userCorrectedCategoryId: Long?,
    val userCorrectedCategoryName: String?,
    val userCorrectedAt: Long?,
    val taxAmount: Double?,
    @ColumnInfo(defaultValue = "0") val isNewCategorySuggestion: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
