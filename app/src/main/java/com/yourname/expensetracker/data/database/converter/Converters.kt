package com.yourname.expensetracker.data.database.converter

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import androidx.room.TypeConverter
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.ai.model.AiArtifactStatus
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.model.AiMode
import com.yourname.expensetracker.domain.ai.model.AiTargetType

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

    // ------------------------------------------------------------------
    // AI enums
    // ------------------------------------------------------------------

    @TypeConverter
    fun fromAiCapability(value: AiCapability): String = value.name

    @TypeConverter
    fun toAiCapability(value: String): AiCapability =
        try { AiCapability.valueOf(value) } catch (_: IllegalArgumentException) { AiCapability.DASHBOARD_BRIEFING }

    @TypeConverter
    fun fromAiMode(value: AiMode): String = value.name

    @TypeConverter
    fun toAiMode(value: String): AiMode =
        try { AiMode.valueOf(value) } catch (_: IllegalArgumentException) { AiMode.AUTO }

    @TypeConverter
    fun fromAiTargetType(value: AiTargetType): String = value.name

    @TypeConverter
    fun toAiTargetType(value: String): AiTargetType =
        try { AiTargetType.valueOf(value) } catch (_: IllegalArgumentException) { AiTargetType.DASHBOARD }

    @TypeConverter
    fun fromAiArtifactStatus(value: AiArtifactStatus): String = value.name

    @TypeConverter
    fun toAiArtifactStatus(value: String): AiArtifactStatus =
        try { AiArtifactStatus.valueOf(value) } catch (_: IllegalArgumentException) { AiArtifactStatus.FAILED }

    @TypeConverter
    fun fromAssistantMessageRole(value: AssistantMessageRole): String = value.name

    @TypeConverter
    fun toAssistantMessageRole(value: String): AssistantMessageRole =
        try { AssistantMessageRole.valueOf(value) } catch (_: IllegalArgumentException) { AssistantMessageRole.SYSTEM }

    @TypeConverter
    fun fromAssistantMessageKind(value: AssistantMessageKind): String = value.name

    @TypeConverter
    fun toAssistantMessageKind(value: String): AssistantMessageKind =
        try { AssistantMessageKind.valueOf(value) } catch (_: IllegalArgumentException) { AssistantMessageKind.ERROR }
}
