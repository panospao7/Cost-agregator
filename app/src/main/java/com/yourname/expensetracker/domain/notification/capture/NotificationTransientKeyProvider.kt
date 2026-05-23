package com.yourname.expensetracker.domain.notification.capture

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides Android Keystore-backed AES-GCM secret keys for transient
 * notification payload encryption. Keys are hardware-backed on supported
 * devices and never leave the secure keystore.
 */
interface NotificationTransientKeyProvider {
    fun getOrCreateSecretKey(version: Int = CURRENT_VERSION): SecretKey

    companion object {
        const val CURRENT_VERSION = 1
    }
}

@Singleton
class AndroidKeystoreNotificationTransientKeyProvider @Inject constructor()
    : NotificationTransientKeyProvider {

    override fun getOrCreateSecretKey(version: Int): SecretKey {
        val alias = "notification_transient_payload_v$version"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
