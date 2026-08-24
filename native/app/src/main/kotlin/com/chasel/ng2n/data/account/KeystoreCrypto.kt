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

/**
 * 账号表落盘前的加解密。**票 15 加的接缝**:实装 [KeystoreCrypto] 要 `AndroidKeyStore` 与
 * `android.util.Base64`,两样在 JVM 单测里都只有会抛的桩,于是 `AccountStore` 的
 * 状态迁移(切号 / 登出 / 冷启读回)在 JVM 上一条也测不了 —— 而票 15 的验收②③正是这些。
 * 加解密算法本身归 [KeystoreCrypto] 的 androidTest 管,单测这一侧塞个直通实现即可。
 */
interface AccountCrypto {

  /** 明文 → 可进 DataStore 的字符串;失败返回 null(调用方按「写不进」降级)。 */
  fun encrypt(plaintext: ByteArray): String?

  /** 反过来;解不开返回 null(调用方退回游客态)。 */
  fun decrypt(blob: String): ByteArray?
}

/**
 * 凭证读写路径上的告警口(**票 60 加**)。
 *
 * 为什么是接口而不是直接 `android.util.Log`:`AccountStore` 的状态迁移全在 JVM 单测里跑,
 * 而单测的 `android.util.Log` 是会抛的桩(本工程没开 `unitTests.isReturnDefaultValues`)——
 * 一旦失败路径上带了日志,那条路径就再也测不了。于是照 [AccountCrypto] 的老规矩收一道接缝:
 * 真机上是 [AndroidAccountStoreLog],单测里是 [NONE]。
 *
 * **绝不打凭证本身**:只打「哪一步失败了、异常是什么类」,uid / cid / 密文一律不进 logcat。
 */
fun interface AccountStoreLog {

  fun warn(message: String, cause: Throwable?)

  companion object {

    /** 单测用的黑洞。 */
    val NONE: AccountStoreLog = AccountStoreLog { _, _ -> }
  }
}

/** logcat 里的真实装。tag 固定 [TAG],真机排查时 `adb logcat -s ng2n-accounts` 就够。 */
class AndroidAccountStoreLog : AccountStoreLog {

  override fun warn(message: String, cause: Throwable?) {
    if (cause == null) Log.w(TAG, message) else Log.w(TAG, message, cause)
  }

  companion object {
    const val TAG = "ng2n-accounts"
  }
}

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
internal class KeystoreCrypto(
  private val alias: String = KEY_ALIAS,
  /** 票 60:失败路径必须在 logcat 里留痕,否则 release 上「丢了」和「解不开」分不开。 */
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
    // 抛而不是 return null:让下面的 onFailure 也能把这一档打进 logcat
    require(raw.size > IV_BYTES) { "密文长度 ${raw.size} 不足以容纳 IV" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      secretKey(),
      GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
    )
    cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
  }.onFailure {
    // AEADBadTagException = 密钥换了/密文被动过;KeyStoreException / UnrecoverableKeyException
    // = 别名不在了(卸载重装、恢复出厂)。两者的处置都是「退游客态」,但排查时要分得开。
    log.warn("账号密文解不开(${it.javaClass.simpleName}),本次按游客态起", it)
  }.getOrNull()

  private fun secretKey(): SecretKey {
    val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

    // 别名不在了。正常首登也会走这里,但如果盘上已经有密文,这一行就是「密钥没了、
    // 数据还在」的实锤 —— 票 60 那次真机登录态丢失,logcat 里本该有的就是它。
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
    /** 换存储结构就换别名(老密钥解不动新数据,自然作废)。 */
    const val KEY_ALIAS = "ng2n.accounts.v1"

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
  }
}
