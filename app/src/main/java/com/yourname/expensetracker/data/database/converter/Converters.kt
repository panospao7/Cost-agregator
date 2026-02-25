package com.yourname.expensetracker.data.database.converter

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import androidx.room.TypeConverter
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransferDirection

class Converters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String {
        return value.name
    }

    @TypeConverter
    fun toTransactionType(value: String): TransactionType {
        return try {
            TransactionType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            TransactionType.UNKNOWN
        }
    }

    @TypeConverter
    fun fromPaymentMethod(value: PaymentMethod): String {
        return value.name
    }

    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod {
        return try {
            PaymentMethod.valueOf(value)
        } catch (e: IllegalArgumentException) {
            PaymentMethod.UNKNOWN
        }
    }

    @TypeConverter
    fun fromBudgetPeriod(value: com.yourname.expensetracker.data.database.entity.BudgetPeriod): String {
        return value.name
    }

    @TypeConverter
    fun toBudgetPeriod(value: String): com.yourname.expensetracker.data.database.entity.BudgetPeriod {
        return try {
            com.yourname.expensetracker.data.database.entity.BudgetPeriod.valueOf(value)
        } catch (e: IllegalArgumentException) {
            com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY
        }
    }

    @TypeConverter
    fun fromTransferDirection(value: TransferDirection?): String? {
        return value?.name
    }

    @TypeConverter
    fun toTransferDirection(value: String?): TransferDirection? {
        return value?.let {
            try {
                TransferDirection.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
