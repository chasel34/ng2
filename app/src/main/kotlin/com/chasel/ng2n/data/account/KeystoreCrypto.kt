package com.chasel.ng2n.data.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface AccountCrypto {

  fun encrypt(plaintext: ByteArray): String?

  fun decrypt(blob: String): ByteArray?
}

fun interface AccountStoreLog {

  fun warn(message: String, cause: Throwable?)

  companion object {

    val NONE: AccountStoreLog = AccountStoreLog { _, _ -> }
  }
}

class AndroidAccountStoreLog : AccountStoreLog {

  override fun warn(message: String, cause: Throwable?) {
    if (cause == null) Log.w(TAG, message) else Log.w(TAG, message, cause)
  }

  companion object {
    const val TAG = "ng2n-accounts"
  }
}

internal class KeystoreCrypto(
  private val alias: String = KEY_ALIAS,
  private val log: AccountStoreLog = AndroidAccountStoreLog(),
) : AccountCrypto {

  override fun encrypt(plaintext: ByteArray): String? = runCatching {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val iv = cipher.iv
    val body = cipher.doFinal(plaintext)
    Base64.encodeToString(iv + body, Base64.NO_WRAP)
  }.onFailure { log.warn("账号表加密失败(${it.javaClass.simpleName}),本次不落盘", it) }
    .getOrNull()

  override fun decrypt(blob: String): ByteArray? = runCatching {
    val raw = Base64.decode(blob, Base64.NO_WRAP)
    require(raw.size > IV_BYTES) { "密文长度 ${raw.size} 不足以容纳 IV" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      secretKey(),
      GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
    )
    cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
  }.onFailure {
    log.warn("账号密文解不开(${it.javaClass.simpleName}),本次按游客态起", it)
  }.getOrNull()

  private fun secretKey(): SecretKey {
    val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

    log.warn("Keystore 里没有 $alias,新建一把;此前存过的账号密文从此解不开", null)
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    generator.init(
      KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(KEY_BITS)
        .build(),
    )
    return generator.generateKey()
  }

  companion object {
    const val KEY_ALIAS = "ng2n.accounts.v1"

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
  }
}
