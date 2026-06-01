package com.yourname.expensetracker.domain.common

import java.security.MessageDigest

fun String.sha256Prefix(length: Int = 12): String {
    require(length > 0) { "sha256Prefix length must be > 0" }
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { "%02x".format(it) }.take(length)
}

/**
 * Returns the full 64-character lowercase SHA-256 hex digest of this string.
 */
fun String.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(this.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Semantic alias for [sha256] intended for content fingerprinting.
 * Produces identical output to [sha256].
 */
fun String.sha256Fingerprint(): String = sha256()
