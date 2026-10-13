 package com.yourname.expensetracker.data.database.entity

 import androidx.room.ColumnInfo
 import androidx.room.Entity
 import androidx.room.ForeignKey
 import androidx.room.Index
 import androidx.room.PrimaryKey

 /**
  * Authoritative contribution ledger for accepted savings operations.
  *
  * Sweep acceptance writes this ledger in the same Room transaction as the
  * savings-goal balance and sweep-plan status. The idempotency key prevents a
  * retry from applying the same allocation twice.
  */
 @Entity(
     tableName = "savings_contribution_ledger",
     indices = [
         Index(value = ["idempotencyKey"], unique = true),
         Index(value = ["goalId"]),
         Index(value = ["timestamp"])
     ],
     foreignKeys = [
         ForeignKey(
             entity = SavingsGoal::class,
             parentColumns = ["id"],
             childColumns = ["goalId"],
             onDelete = ForeignKey.CASCADE
         )
     ]
 )
 data class SavingsContributionLedgerEntry(
     @PrimaryKey(autoGenerate = true)
     val id: Long = 0,
     val idempotencyKey: String,
     val goalId: Long,
     val amount: Double,
     @ColumnInfo(defaultValue = "'EUR'")
     val currency: String = "EUR",
     val timestamp: Long,
     val source: String,
     val sweepPlanId: Long? = null
 )
