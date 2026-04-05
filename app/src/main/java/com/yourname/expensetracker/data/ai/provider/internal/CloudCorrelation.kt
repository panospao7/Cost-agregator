package com.yourname.expensetracker.data.ai.provider.internal

import java.util.UUID

object CloudCorrelation {
    fun newCorrelationId(): String = UUID.randomUUID().toString().take(8)
}
