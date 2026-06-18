package com.yourname.expensetracker.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ## DB-8: CASCADE audit — MerchantAlias
 *
 * ### CASCADE on canonicalId (line 17)
 * **What gets cascade-deleted:** Deleting a row from `merchant_canonicals` removes
 * ALL `merchant_aliases` rows that reference it. This silently erases the complete
 * alias history (raw names, normalization mappings, occurrence counts) for that
 * canonical merchant.
 *
 * **Appropriateness assessment:** CASCADE is appropriate because:
 * 1. Merchant canonical entries are the authoritative identity record; if a
 *    canonical entry is removed, its aliases have no logical meaning.
 * 2. Aliases are derived/resolved data, not primary financial history.
 * 3. The alternative (RESTRICT) would prevent cleanup of orphaned canonical entries.
 *
 * **Migration path if change is needed:** Change to `onDelete = ForeignKey.RESTRICT`
 * and require explicit alias deletion before canonical removal. Add a repository
 * method `deleteCanonicalWithAliases(canonicalId)` that atomically deletes aliases
 * first, then the canonical row.
 */
@Entity(
    tableName = "merchant_aliases",
    foreignKeys = [
        ForeignKey(
            entity = MerchantCanonical::class,
            parentColumns = ["id"],
            childColumns = ["canonicalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rawName"], unique = true),
        Index(value = ["normalizedKey"], unique = true),
        Index(value = ["canonicalId"])
    ]
)
data class MerchantAlias(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rawName: String,        // e.g., "MCDONALD'S #1234"
    val normalizedKey: String,   // e.g., "mcdonalds1234"
    val canonicalId: Long,
    @ColumnInfo(defaultValue = "1") val occurrenceCount: Int = 1,
    @ColumnInfo(defaultValue = "0") val isUserDefined: Boolean = false,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val createdAt: Long = 0L,
    /** Must be set to timeProvider.now() at creation. 0L = unset (sentinel). */
    val lastUsedAt: Long = 0L
)
