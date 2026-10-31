package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import io.mockk.coEvery
import io.mockk.mockk

/**
 * S6c-F.2 constructor-only test helper (S6c-3 pattern): a [RecurringInstallmentEligibility] over a
 * reader that reports no installment plans, so consumers that gained the dependency behave exactly
 * as before. The reader is only built on first read (dagger.Lazy), like in production.
 */
internal fun noPlansInstallmentEligibility(): RecurringInstallmentEligibility {
    val noPlansReader = mockk<InstallmentEntryLinkReader>()
    coEvery { noPlansReader.linkedExpenseIds() } returns emptySet()
    coEvery { noPlansReader.planPurchaseExpenseIds() } returns emptySet()
    return RecurringInstallmentEligibility(dagger.Lazy { noPlansReader })
}
