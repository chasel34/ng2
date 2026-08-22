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
 * - 读不到/解不开一律退回空表(游客态),**绝不抛**。
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
) : CredentialSource {

  /** 账号表订阅口。游客态是 [EMPTY_ACCOUNTS]。 */
  val accounts: Flow<AccountsState> = dataStore.data
    .catch { cause ->
      // 存档读不了不该把 app 挡在启动那一步 —— 当游客态起,登录后再写回
      if (cause is IOException) emit(emptyPreferences()) else throw cause
    }
    .map { prefs -> decodeState(prefs[KEY]) }

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

  private suspend fun mutate(transform: (AccountsState) -> AccountsState) {
    dataStore.edit { prefs ->
      val next = transform(decodeState(prefs[KEY]))
      val blob = crypto.encrypt(JSON.encodeToString(next).toByteArray(Charsets.UTF_8))
      if (blob == null) {
        // Keystore 用不了(极端:密钥被系统清掉且建不出新的)。写不进就只活在内存 ——
        // 功能还能用,重启后回游客态(RN 版同款降级)
        prefs.remove(KEY)
      } else {
        prefs[KEY] = blob
      }
    }
  }

  /**
   * 解密 + 校验。这份 JSON 可能出自旧版本 app,一律当外部输入:
   * 坏账号剔除、currentUid 不在表里就落到第一个、整串坏掉退回空表。
   */
  private fun decodeState(blob: String?): AccountsState {
    if (blob == null) return EMPTY_ACCOUNTS
    val plain = crypto.decrypt(blob) ?: return EMPTY_ACCOUNTS
    val parsed = runCatching {
      JSON.decodeFromString<AccountsState>(plain.toString(Charsets.UTF_8))
    }.getOrNull() ?: return EMPTY_ACCOUNTS
    return sanitizeAccounts(parsed)
  }

  companion object {
    /** DataStore 文件名(与设置分开:凭证的读写频率与生命周期都不一样)。 */
    const val FILE_NAME = "ng2n-accounts"

    /** 换存储结构就换 key,老数据自然作废,不用写迁移。 */
    private val KEY = stringPreferencesKey("accounts.v1")

    private val JSON = Json {
      ignoreUnknownKeys = true
      isLenient = true
      encodeDefaults = true
      explicitNulls = false
    }
  }
}
