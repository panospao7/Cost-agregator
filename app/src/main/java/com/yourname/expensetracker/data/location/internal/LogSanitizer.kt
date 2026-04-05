package com.yourname.expensetracker.data.location.internal

fun String.anonymizeForLog(): String = hashCode().toUInt().toString(16)
