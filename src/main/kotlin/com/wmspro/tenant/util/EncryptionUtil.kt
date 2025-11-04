package com.wmspro.tenant.util

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Basic encryption utility for sensitive data like passwords
 * Uses AES encryption with a static key (for basic encryption requirement)
 *
 * Note: For production, consider using a more secure key management system
 */
object EncryptionUtil {

    // Basic encryption key (16 bytes for AES-128)
    // In production, this should be stored securely (environment variable, secrets manager, etc.)
    private const val SECRET_KEY = "WmsPr0T3nantK3y!" // 16 characters = 128 bits

    private val keySpec = SecretKeySpec(SECRET_KEY.toByteArray(), "AES")

    /**
     * Encrypts a plain text string
     */
    fun encrypt(plainText: String?): String? {
        if (plainText.isNullOrBlank()) return plainText

        return try {
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray())
            Base64.getEncoder().encodeToString(encryptedBytes)
        } catch (e: Exception) {
            // If encryption fails, return original (fallback)
            plainText
        }
    }

    /**
     * Decrypts an encrypted string
     */
    fun decrypt(encryptedText: String?): String? {
        if (encryptedText.isNullOrBlank()) return encryptedText

        return try {
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.getDecoder().decode(encryptedText)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes)
        } catch (e: Exception) {
            // If decryption fails, return original (might be unencrypted legacy data)
            encryptedText
        }
    }

    /**
     * Masks a password for display purposes
     */
    fun maskPassword(password: String?): String {
        return if (password.isNullOrBlank()) "" else "********"
    }
}
