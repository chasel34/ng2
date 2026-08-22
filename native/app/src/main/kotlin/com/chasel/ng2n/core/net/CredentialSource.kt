package com.chasel.ng2n.core.net

/**
 * 一次请求要带的登录凭证。`token` 就是 Cookie `ngaPassportCid`
 * (RN 版 `NgaCredentials`:`{ uid, token }`)。
 *
 * 纯数据、零 Android 依赖:反封锁链(票 06)的「换账号重试」那一档只认这个,
 * 不认 Room / DataStore / Keystore。
 */
data class Credential(
  val uid: String,
  val token: String,
)

/**
 * core 层看凭证的唯一口子(票 14 定的边界)。
 *
 * `accounts.v1` 的语义照抄 RN 版:**每请求现读**——切号之后下一个请求就用新 cookie,
 * 在途的那个请求仍归发起它的账号(RN 版 `currentCredentials()` 就是每次 fetch 现取)。
 * 所以这里是 suspend 的读函数而不是一份快照。
 *
 * 实现在 `data/account/AccountStore.kt`(DataStore + Android Keystore AES-GCM)。
 * 单测里塞个假实现即可,core 不必知道凭证存在哪。
 */
interface CredentialSource {

  /** 当前账号的凭证;游客态是 null。 */
  suspend fun current(): Credential?

  /**
   * 全部已登录账号的凭证,顺序同账号管理页。
   * 反封锁链「换账号重试」那一档要用(ADR-0002)。
   */
  suspend fun all(): List<Credential>
}
