package com.yourname.expensetracker.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt

data class PendingReviewWithReceipt(
    @Embedded val review: PendingReview,
    
    @Relation(
        parentColumn = "scannedReceiptId",
        entityColumn = "id"
    )
    val receipt: ScannedReceipt?
)
