package com.chasel.ng2n.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chasel.ng2n.core.net.Credential
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.di.AccountPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多账号凭证仓库 —— RN 版 SecureStore 单键 `accounts.v1` 的对应物。
 *
 * ## 实现选择(票面留白的「实现时定」项)
 *
 * **自写 `AndroidKeyStore` + `Cipher` 封装([KeystoreCrypto])+ Preferences DataStore**,
 * 而不是 `EncryptedFile`:`androidx.security:security-crypto` 已弃用,不引入。
 * 载荷 = 整张账号表的 JSON → AES-256-GCM → Base64 → DataStore 的一个 String 键。
 * 密钥别名 `ng2n.accounts.v1`(见 [KeystoreCrypto.KEY_ALIAS])。
 *
 * 存**一整张表**而不是每账号一条:RN 版就是一个键,而且「当前账号」与「账号顺序」
 * 本来就是整体语义,拆开存反而要处理半张表写成功的情况。
 *
 * ## `accounts.v1` 的语义(照抄)
 *
 * - 可同时登录多个,有且仅有一个当前账号;
 * - **每请求现读**:[current] 是 suspend 的,每次都读现值 ——
 *   切号之后**下一个请求**就用新 cookie,在途的那个请求仍归发起它的账号;
 * - 读不到/解不开一律退回空表(游客态),**绝不抛**;
 * - **票 60 补**:「解不开」只影响这一次**读**。密文原样留在盘上,不会被下一次写覆盖掉
 *   (挪到 `accounts.v1.unreadable` 留证),失败也一定进 logcat(`ng2n-accounts` tag)——
 *   `install -r` 之后一次读失败就把凭证永久抹掉,是票 60 认定的「不可再犯」项。
 *
 * ## 冷启动(修 P2-04)
 *
 * RN 版用的是**同步** `SecureStore.getItem`,理由是「冷启动第一屏就要知道登录态」——
 * 那正是审计 P2-04 点名的同步磁盘 IO。这一版全部 suspend:首屏先按游客态渲染,
 * 账号读回来之后 [accounts] 这条 Flow 会让订阅方重渲一次。
 */
@Singleton
class AccountStore @Inject constructor(
  @AccountPreferences private val dataStore: DataStore<Preferences>,
  /**
   * 落盘前的加解密。真实装是 [KeystoreCrypto](`di/DataModule.kt` 里绑的);
   * 收成构造参数是票 15 加的接缝 —— 见 [AccountCrypto] 的注释。
   */
  private val crypto: AccountCrypto,
  /**
   * 失败路径的告警口(票 60)。默认值是给单测的黑洞;app 里由 `di/DataModule.kt` 注入
   * [AndroidAccountStoreLog] —— Hilt 不认默认值,两边都得有。
   */
  private val log: AccountStoreLog = AccountStoreLog.NONE,
) : CredentialSource {

  /** 「存档读不出」只在 logcat 里喊一次,别让每一次 DataStore 变更都刷屏。 */
  private val warnedUnreadable = AtomicBoolean(false)

  /** 账号表订阅口。游客态是 [EMPTY_ACCOUNTS]。 */
  val accounts: Flow<AccountsState> = dataStore.data
    .catch { cause ->
      // 存档读不了不该把 app 挡在启动那一步 —— 当游客态起,登录后再写回
      if (cause is IOException) emit(emptyPreferences()) else throw cause
    }
    .map { prefs ->
      when (val stored = readStored(prefs[KEY])) {
        is Stored.Readable -> stored.state
        is Stored.Unreadable -> {
          if (warnedUnreadable.compareAndSet(false, true)) {
            log.warn(
              "账号存档读不出(${stored.reason}),本次按游客态起;密文原样留在盘上,没有清除",
              null,
            )
          }
          EMPTY_ACCOUNTS
        }
      }
    }

  /**
   * 当前账号 uid;游客态是 null。**票 15 加**:按 uid 隔离的缓存/查询键都读它
   * (修 P1-02 —— RN 版收藏夹的 TanStack Query key 漏了 uid,切号后会读到上一个账号的夹)。
   * `distinctUntilChanged` 让「改了别的账号的名字」这类无关变更不触发下游重查。
   */
  val currentUid: Flow<String?> = accounts.map { it.currentUid }.distinctUntilChanged()

  /** 当前账号;游客态是 null。**每次现读**。 */
  suspend fun currentAccount(): NgaAccount? = currentAccountOf(accounts.first())

  override suspend fun current(): Credential? =
    currentAccount()?.let { Credential(uid = it.uid, token = it.cid) }

  /** 全部已登录账号的凭证,顺序同账号管理页(反封锁链「换账号重试」那一档要用)。 */
  override suspend fun all(): List<Credential> =
    accounts.first().accounts.map { Credential(uid = it.uid, token = it.cid) }

  /** 登录成功落账号:同 uid 重登就地刷新,新账号追加到末尾,并立刻成为当前账号。 */
  suspend fun upsert(account: NgaAccount) = mutate { addAccount(it, account) }

  /** 切换当前账号。 */
  suspend fun switchTo(uid: String) = mutate { switchAccount(it, uid) }

  /** 退出某账号。退的是当前账号时落到剩余第一个;全退光即游客态。 */
  suspend fun remove(uid: String) = mutate { removeAccount(it, uid) }

  /** 抽屉账号头左右滑循环切号。不足两个账号是 no-op。 */
  suspend fun cycle(step: Int) {
    val next = cycleAccountUid(accounts.first(), step) ?: return
    switchTo(next)
  }

  /**
   * 读—改—写。**票 60 的两条硬规矩**(「装新包丢一次登录态」之后加的):
   *
   * 1. **读不出的密文绝不当空表覆盖掉** —— 老写法把 `decodeState` 的「解不开 → 空表」
   *    直接当成写入基线,于是任何一次解密失败之后的第一次写(切号、登录、甚至改个名字)
   *    就把那串密文永久抹掉了:本来只是「这次解不开」,写完变成「真的没了」。
   *    现在原文搬到 [KEY_UNREADABLE] 留证,新表从空表起。
   * 2. **加密失败不删旧值** —— 老写法 `prefs.remove(KEY)`:Keystore 临时不可用的那一下,
   *    盘上那份好好的凭证被顺手删了。现在保持原样,只有「本来就是要清空」
   *    (退光所有账号)时才真删。
   */
  private suspend fun mutate(transform: (AccountsState) -> AccountsState) {
    dataStore.edit { prefs ->
      val stored = readStored(prefs[KEY])
      val base = when (stored) {
        is Stored.Readable -> stored.state
        is Stored.Unreadable -> {
          prefs[KEY_UNREADABLE] = stored.blob
          log.warn(
            "账号存档读不出(${stored.reason}),这次写入从空表起;" +
              "原密文已挪到 $KEY_UNREADABLE_NAME 留证,未丢弃",
            null,
          )
          EMPTY_ACCOUNTS
        }
      }
      val next = transform(base)
      val blob = crypto.encrypt(JSON.encodeToString(next).toByteArray(Charsets.UTF_8))
      when {
        blob != null -> prefs[KEY] = blob
        // 加密失败 + 目标就是空表(退光了):该清就清,不留悬空凭证
        next.accounts.isEmpty() -> prefs.remove(KEY)
        // 加密失败 + 还有账号:盘上那份保持原样,这次改动只活在内存里。
        // 重启后回到上一次成功落盘的状态,而不是回游客态。
        else -> log.warn("账号表写不进(加密失败),盘上旧存档保持不变", null)
      }
    }
  }

  /**
   * 盘上那一格的三种结局。**票 60**:老代码把「没存过」和「存了但读不出」都折成同一个
   * 空表,于是调用方无从知道自己正踩在哪一种上 —— 而这两种的写入语义正好相反
   * (前者随便写,后者写下去就毁证)。
   */
  private sealed interface Stored {

    /** 没存过(游客态),或读回来了。 */
    data class Readable(val state: AccountsState) : Stored

    /** 有密文但解不开 / 解出来不是合法 JSON。[blob] 是原样的密文,**不许丢**。 */
    data class Unreadable(val blob: String, val reason: String) : Stored
  }

  /**
   * 解密 + 校验。这份 JSON 可能出自旧版本 app,一律当外部输入:
   * 坏账号剔除、currentUid 不在表里就落到第一个。
   */
  private fun readStored(blob: String?): Stored {
    if (blob == null) return Stored.Readable(EMPTY_ACCOUNTS)
    val plain = crypto.decrypt(blob)
      ?: return Stored.Unreadable(blob, "密文解不开(密钥丢失或密文被改)")
    val parsed = runCatching {
      JSON.decodeFromString<AccountsState>(plain.toString(Charsets.UTF_8))
    }.getOrNull() ?: return Stored.Unreadable(blob, "解出来不是合法的账号表 JSON")
    return Stored.Readable(sanitizeAccounts(parsed))
  }

  companion object {
    /** DataStore 文件名(与设置分开:凭证的读写频率与生命周期都不一样)。 */
    const val FILE_NAME = "ng2n-accounts"

    /**
     * 用真实 Keystore 加密的实例。app 里正常走 Hilt(`di/DataModule.kt`);
     * 这个口子是给**设备侧测试**用的 —— androidTest 要读的正是设备上那份真存档
     * (票 15 的登录态冒烟),而 [KeystoreCrypto] 是 internal。
     */
    fun withKeystore(dataStore: DataStore<Preferences>): AccountStore =
      AccountStore(dataStore, KeystoreCrypto(), AndroidAccountStoreLog())

    /** 换存储结构就换 key,老数据自然作废,不用写迁移。 */
    private val KEY = stringPreferencesKey("accounts.v1")

    /**
     * 读不出来的那串密文的存放处(票 60)。只写不读:留着是为了「丢了」和「解不开」
     * 在事后能分开 —— 真机上 `adb shell run-as`(debug)或备份出的
     * `datastore/ng2n-accounts.preferences_pb` 里,这一格存在即证明数据还在、只是钥匙没了。
     * 每次覆盖成最近一份,不会长。
     */
    const val KEY_UNREADABLE_NAME = "accounts.v1.unreadable"
    private val KEY_UNREADABLE = stringPreferencesKey(KEY_UNREADABLE_NAME)

    private val JSON = Json {
      ignoreUnknownKeys = true
      isLenient = true
      encodeDefaults = true
      explicitNulls = false
    }
  }
}
