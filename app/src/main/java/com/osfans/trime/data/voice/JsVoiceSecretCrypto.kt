/* SPDX-License-Identifier: LGPL-2.1-or-later */
package com.osfans.trime.data.voice

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 插件密钥的落盘加密。
 *
 * 密钥材料只存在于 AndroidKeyStore（TEE/StrongBox 视设备而定），应用只拿得到
 * [SecretKey] 句柄；密文与随机 IV 一起以 Base64 存 SharedPreferences。
 * 这样即使设备被 root、SharedPreferences 被直接读走，也拿不到明文 API key。
 *
 * 不引入第三方加密库：AES/GCM 在 API 23 起就是平台能力。芝版 minSdk 是 21，
 * 21/22 上退化为带固定混淆的 Base64（只能防肩窥，不具备 Keystore 的硬件保护）。
 *
 * [hmacSha1] 是给插件签名用的：腾讯云等服务的请求签名要求 HMAC-SHA1，
 * 签名在宿主内完成，脚本只拿到签名结果，永远拿不到密钥本身。
 */
internal object JsVoiceSecretCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "js_voice_plugin_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val LEGACY_PREFIX = "plain:"
    private val LEGACY_KEY = "zhi-trime-voice".toByteArray(Charsets.UTF_8)

    /** 在宿主内解析密钥并签名；脚本只收到签名，收不到密钥。 */
    fun hmacSha1(
        secretField: String,
        text: String,
        secretFor: (String) -> String?,
    ): ByteArray {
        val secret = secretFor(secretField)?.takeIf { it.isNotBlank() }
            ?: throw JsVoiceSecretMissingException(secretField)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    fun encrypt(plaintext: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val obfuscated = xor(plaintext.toByteArray(Charsets.UTF_8))
            return LEGACY_PREFIX + Base64.encodeToString(obfuscated, Base64.NO_WRAP)
        }
        return encryptKeystore(plaintext)
    }

    fun decrypt(payload: String): String? {
        if (payload.startsWith(LEGACY_PREFIX)) {
            return runCatching {
                val raw = Base64.decode(payload.removePrefix(LEGACY_PREFIX), Base64.NO_WRAP)
                String(xor(raw), Charsets.UTF_8)
            }.getOrNull()
        }
        return decryptKeystore(payload)
    }

    private fun xor(data: ByteArray): ByteArray = ByteArray(data.size) { i ->
        (data[i].toInt() xor LEGACY_KEY[i % LEGACY_KEY.size].toInt()).toByte()
    }

    private fun encryptKeystore(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    private fun decryptKeystore(payload: String): String? = runCatching {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        if (raw.size <= IV_LENGTH) return null
        val iv = raw.copyOfRange(0, IV_LENGTH)
        val ciphertext = raw.copyOfRange(IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
