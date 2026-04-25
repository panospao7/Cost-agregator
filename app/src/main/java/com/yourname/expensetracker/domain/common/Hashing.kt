package com.yourname.expensetracker.domain.common

import java.security.MessageDigest

fun String.sha256Prefix(length: Int = 12): String {
    require(length > 0) { "sha256Prefix length must be > 0" }
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { "%02x".format(it) }.take(length)
}
