package com.yourname.expensetracker.ui.screens.installments

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentMinorUnits
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Pure helpers shared by the installment detail and history mappers (loan-installments S10a-2).
 * Read-only display mapping; no I/O.
 */

/** Minor units to a displayed amount with its explicit currency (2 decimals for EUR and USD). */
internal fun installmentMoney(minor: Long, currency: InstallmentCurrency): MoneyDisplayUi =
    MoneyDisplayUi.from(InstallmentMinorUnits.fromMinor(minor), currency.code)

/** A known amount or an explicit "unavailable"; unavailable is never rendered as zero (INV-17). */
internal fun installmentAmountUi(value: InstallmentAmount, currency: InstallmentCurrency): InstallmentAmountUi =
    when (value) {
        is InstallmentAmount.Available -> InstallmentAmountUi.Money(installmentMoney(value.minor, currency))
        is InstallmentAmount.Unavailable -> InstallmentAmountUi.Unavailable(value.reason)
    }

/** Civil epoch day to a localized medium date. */
internal fun formatInstallmentDate(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
