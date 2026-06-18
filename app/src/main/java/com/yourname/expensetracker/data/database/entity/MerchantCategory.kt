package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ## DB-8: CASCADE audit — MerchantCategory
 *
 * ### CASCADE on categoryId (line 17)
 * **What gets cascade-deleted:** Deleting a row from the `categories` table removes
 * all `merchant_categories` rows that reference it. This erases the merchant-to-category
 * mapping history, which is metadata — not financial history directly — but losing
 * these mappings can degrade future classification, confidence scoring, and
 * merchant-category recommendations.
 *
 * **Appropriateness assessment:** CASCADE is acceptable here because:
 * 1. A category deletion is an intentional admin/configuration action.
 * 2. Merchant-category mappings are derived metadata that become stale/useless
 *    once the target category no longer exists.
 * 3. The alternative (SET NULL) would leave orphaned rows with null categoryId
 *    that could cause NPEs or silent misclassification in downstream queries.
 *
 * **Migration path if change is needed:** Change to `onDelete = ForeignKey.SET_NULL`
 * and update all consumer code to handle nullable `categoryId`. Add a background
 * cleanup job to delete rows where `categoryId IS NULL` after a grace period.
 */
@Entity(
    tableName = "merchant_categories",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"]), Index(value = ["normalizedCanonicalName"])]
)
data class MerchantCategory(
    @PrimaryKey
    val merchantPattern: String,
    val categoryId: Long,
    @ColumnInfo(defaultValue = "1.0") val confidence: Float = 1.0f,
    @ColumnInfo(defaultValue = "1") val timesUsed: Int = 1,
    val normalizedCanonicalName: String? = null
)
