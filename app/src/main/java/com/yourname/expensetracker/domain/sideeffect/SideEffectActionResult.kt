package com.yourname.expensetracker.domain.sideeffect

data class SideEffectActionResult(
    val idempotencyKey: String,
    val name: String,
    val outcome: SideEffectOutcome
)
