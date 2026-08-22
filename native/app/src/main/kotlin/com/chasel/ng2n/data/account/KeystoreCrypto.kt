package com.chasel.ng2n.data.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 的 AES-GCM 小封装 —— 账号凭证(`ngaPassportCid`)落盘前过这一道。
 *
 * ## 为什么自己写
 *
 * `androidx.security:security-crypto`(`EncryptedFile` / `EncryptedSharedPreferences`)
 * **已弃用**,Google 在 1.1.0 之后不再维护、也不推荐新项目引入。所以这里直接用平台的
 * `AndroidKeyStore` + `javax.crypto`:一个别名、一把 AES-256 密钥、GCM 模式,
 * 加起来不到 60 行,没有第三方依赖,也不会跟着某个弃用库一起烂掉。
 *
 * ## 参数
 *
 * - 密钥别名 [KEY_ALIAS] = `ng2n.accounts.v1` —— **换存储结构就换别名**,
 *   与「换 key/表名,老数据作废」是同一条策略:老密钥解不动新数据,退回游客态重新登录。
 * - AES-256 / GCM / NoPadding;IV 由 `Cipher` 自己随机生成(**绝不复用**),
 *   密文格式 = `IV(12 字节) || ciphertext+tag`,整体 Base64 后进 DataStore。
 * - **不设** `setUserAuthenticationRequired` —— 那要求每次读都过一次锁屏认证,
 *   而凭证是每个请求现读的(`CredentialSource`),没法交互。
 *
 * ## 失败即游客态
 *
 * 密钥被系统清掉(改锁屏、恢复出厂、备份还原)时 `decrypt` 返回 null,
 * 上层退回空账号表 = 游客态,登录一次就好。**绝不抛到调用方**。
 */
internal class KeystoreCrypto(private val alias: String = KEY_ALIAS) {

  fun encrypt(plaintext: ByteArray): String? = runCatching {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
    val iv = cipher.iv
    val body = cipher.doFinal(plaintext)
    Base64.encodeToString(iv + body, Base64.NO_WRAP)
  }.getOrNull()

  fun decrypt(blob: String): ByteArray? = runCatching {
    val raw = Base64.decode(blob, Base64.NO_WRAP)
    if (raw.size <= IV_BYTES) return null
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      secretKey(),
      GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
    )
    cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
  }.getOrNull()

  private fun secretKey(): SecretKey {
    val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

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
    /** 换存储结构就换别名(老密钥解不动新数据,自然作废)。 */
    const val KEY_ALIAS = "ng2n.accounts.v1"

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
  }
}
