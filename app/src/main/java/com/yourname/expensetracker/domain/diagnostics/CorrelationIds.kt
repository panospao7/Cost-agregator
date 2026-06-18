package com.yourname.expensetracker.domain.diagnostics

import java.util.UUID

object CorrelationIds {
    fun newId(): String = UUID.randomUUID().toString()
}
