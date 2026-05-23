package com.yourname.expensetracker.domain.transaction.validation

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import javax.inject.Inject
import javax.inject.Singleton

interface TransactionDatePolicy {
    fun latestAllowedTransactionDate(
        now: Long,
        source: ExpenseSource,
        transactionType: TransactionType
    ): Long

    fun describeFutureDatePolicy(): String
}

@Singleton
class DefaultTransactionDatePolicy @Inject constructor() : TransactionDatePolicy {
    override fun latestAllowedTransactionDate(
        now: Long,
        source: ExpenseSource,
        transactionType: TransactionType
    ): Long {
        return TimePeriodUtils.addDays(
            now,
            AppConfig.Transaction.DEFAULT_FUTURE_DATE_TOLERANCE_DAYS
        )
    }

    override fun describeFutureDatePolicy(): String {
        return "Date cannot be more than ${AppConfig.Transaction.DEFAULT_FUTURE_DATE_TOLERANCE_DAYS} day(s) in the future"
    }
}
