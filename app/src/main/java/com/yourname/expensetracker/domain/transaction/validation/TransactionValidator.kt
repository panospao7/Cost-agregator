package com.yourname.expensetracker.domain.transaction.validation

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionValidator @Inject constructor(
    private val timeProvider: TimeProvider
) {
    fun validateCreate(request: CreateExpenseRequest): List<TransactionValidationError> {
        return validateFields(
            amount = request.amount,
            merchant = request.merchant,
            currency = request.currency,
            date = request.date,
            transactionType = request.transactionType,
            transferDirectionPresent = request.transferDirection != null,
            transferAccountName = request.transferAccountName,
            isNotMine = request.isNotMine,
            isSharedExpense = request.isSharedExpense,
            latitude = request.latitude,
            longitude = request.longitude
        )
    }

    fun validateFinalExpenseState(
        amount: Double,
        merchant: String,
        currency: String,
        date: Long,
        transactionType: TransactionType,
        transferDirectionPresent: Boolean,
        transferAccountName: String?,
        isNotMine: Boolean,
        isSharedExpense: Boolean,
        latitude: Double?,
        longitude: Double?
    ): List<TransactionValidationError> {
        return validateFields(
            amount = amount,
            merchant = merchant,
            currency = currency,
            date = date,
            transactionType = transactionType,
            transferDirectionPresent = transferDirectionPresent,
            transferAccountName = transferAccountName,
            isNotMine = isNotMine,
            isSharedExpense = isSharedExpense,
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun validateFields(
        amount: Double,
        merchant: String,
        currency: String,
        date: Long,
        transactionType: TransactionType,
        transferDirectionPresent: Boolean,
        transferAccountName: String?,
        isNotMine: Boolean,
        isSharedExpense: Boolean,
        latitude: Double?,
        longitude: Double?
    ): List<TransactionValidationError> {
        val errors = mutableListOf<TransactionValidationError>()
        val now = timeProvider.now()

        if (!amount.isFinite() || amount <= 0) {
            errors += TransactionValidationError(
                code = "AMOUNT_NOT_POSITIVE_FINITE",
                field = "amount",
                message = "Amount must be positive and finite"
            )
        }

        if (amount > 1_000_000) {
            errors += TransactionValidationError(
                code = "AMOUNT_EXCEEDS_MAX",
                field = "amount",
                message = "Amount exceeds maximum (1,000,000)"
            )
        }

        if (merchant.isBlank()) {
            errors += TransactionValidationError(
                code = "MERCHANT_BLANK",
                field = "merchant",
                message = "Merchant cannot be blank"
            )
        }

        if (merchant == "Unknown" || merchant == "Parsing Failed") {
            errors += TransactionValidationError(
                code = "MERCHANT_PLACEHOLDER",
                field = "merchant",
                message = "Merchant placeholder not allowed for real expenses"
            )
        }

        if (currency.isBlank()) {
            errors += TransactionValidationError(
                code = "CURRENCY_REQUIRED",
                field = "currency",
                message = "Currency is required"
            )
        } else if (!CURRENCY_ISO_PATTERN.matches(currency)) {
            errors += TransactionValidationError(
                code = "CURRENCY_INVALID_ISO",
                field = "currency",
                message = "Currency must be a 3-letter ISO code (e.g. EUR, USD)"
            )
        }

        if (date <= 0) {
            errors += TransactionValidationError(
                code = "DATE_NOT_POSITIVE",
                field = "date",
                message = "Date must be positive"
            )
        }

        if (date > TimePeriodUtils.addDays(now, 1)) {
            errors += TransactionValidationError(
                code = "DATE_IN_FUTURE",
                field = "date",
                message = "Date cannot be in the future"
            )
        }

        if (transactionType == TransactionType.TRANSFER) {
            if (!transferDirectionPresent) {
                errors += TransactionValidationError(
                    code = "TRANSFER_DIRECTION_REQUIRED",
                    field = "transferDirection",
                    message = "Transfer direction is required for TRANSFER transactions"
                )
            }

            if (transferAccountName.isNullOrBlank()) {
                errors += TransactionValidationError(
                    code = "TRANSFER_ACCOUNT_REQUIRED",
                    field = "transferAccountName",
                    message = "Transfer account name is required for TRANSFER transactions"
                )
            }
        }

        if (isNotMine && isSharedExpense) {
            errors += TransactionValidationError(
                code = "OWNERSHIP_CONFLICT",
                field = "ownership",
                message = "Cannot be both not-mine and shared"
            )
        }

        if (latitude != null && longitude == null) {
            errors += TransactionValidationError(
                code = "LATITUDE_REQUIRES_LONGITUDE",
                field = "longitude",
                message = "Latitude requires longitude"
            )
        }

        if (longitude != null && latitude == null) {
            errors += TransactionValidationError(
                code = "LONGITUDE_REQUIRES_LATITUDE",
                field = "latitude",
                message = "Longitude requires latitude"
            )
        }

        if (latitude != null && latitude !in -90.0..90.0) {
            errors += TransactionValidationError(
                code = "LATITUDE_OUT_OF_RANGE",
                field = "latitude",
                message = "Latitude out of range"
            )
        }

        if (longitude != null && longitude !in -180.0..180.0) {
            errors += TransactionValidationError(
                code = "LONGITUDE_OUT_OF_RANGE",
                field = "longitude",
                message = "Longitude out of range"
            )
        }

        return errors
    }

    companion object {
        private val CURRENCY_ISO_PATTERN = Regex("^[A-Z]{3}$")
    }
}
