package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.USER_AGENT_PROFILES
import com.chasel.ng2n.core.net.UserAgentProfile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统 WebView UA 的取值与缓存(票 35 的死锁那一半)。
 *
 * ## 为什么不能是一把 `lazy`
 *
 * `WebSettings.getDefaultUserAgent()` **第一次**调用要把 WebView provider 拉起来,而
 * provider 的启动只能在 UI 线程跑:在别的线程调它,它就 `CountDownLatch.await()`
 * 等主线程把那段任务跑完。旧实现(`di/NetworkModule.kt` 的
 * `val systemUserAgent by lazy { WebSettings.getDefaultUserAgent(context) }`)把这个调用
 * 包在 `SynchronizedLazyImpl` 里 —— 求值全程持锁,于是「等主线程」变成了
 * **「攥着锁等主线程」**:通知轮询线程先进来占住锁去等主线程,主线程随后也来求同一个
 * `lazy`(首页 `LaunchedEffect` 跑在 `AndroidUiDispatcher` 上),两边互等 → ANR。
 * 登录态必现,游客态两条路都不发请求,所以永远碰不到(票 35 的现场分析)。
 *
 * ## 这一版的三条纪律
 *
 * 1. **求值只在主线程发生**([prewarm] 在 `Application.onCreate` 里跑,或由 [get] 在
 *    发现自己就在主线程时顺手算掉)。主线程调 `getDefaultUserAgent` 是安全的 ——
 *    provider 的初始化任务就在这条线程上执行,不存在等谁。
 * 2. **非主线程读永不阻塞**:[get] 拿不到现成值时,把求值 [postToMainThread] 出去,
 *    自己**立刻**返回兜底 UA。多求一遍没有任何副作用,而多等一次就是票 35。
 * 3. **全程不持锁**:值放 `@Volatile`,写入是一次引用赋值;并发读只会读到「旧值或新值」,
 *    两者都是合法 UA。[posted] 只是「别重复往主线程排队」的去重旗,不是互斥锁。
 *
 * ## 兜底值的语义
 *
 * [fallback] 默认取 `USER_AGENT_PROFILES[WEBVIEW]` —— 与 RN 版
 * (`src/core/net/constants.ts` 的 `webview` 档)**同一个常量、同一条口径**:
 * 「设备侧注入不到系统 UA 时用这个」。反封锁链看到的仍然是一条像样的移动端浏览器 UA,
 * `X-User-Agent: Nga_Official` 那条辅助头照旧由 `runAttempt` 拼(API 文档 §0.3),
 * 档位轮换的语义一行没动。
 *
 * **没有拿 `System.getProperty("http.agent")` 兜底**:Android 上它是 Dalvik 的 UA
 * (`Dalvik/2.1.0 (Linux; U; Android …)`),压根不是浏览器 UA —— 拿它去冒充 WebView 档
 * 等于给服务端递一个新的识别特征,比常量兜底更危险。
 */
class SystemUserAgent(
  /** 当前是不是主线程。生产实现是 `Looper.myLooper() == Looper.getMainLooper()`。 */
  private val onMainThread: () -> Boolean,
  /** 真正去问系统要 UA(生产实现是 `WebSettings.getDefaultUserAgent(context)`)。 */
  private val readSystemUserAgent: () -> String,
  /** 把一段活儿丢到主线程去跑(生产实现是 `Handler(mainLooper).post`)。 */
  private val postToMainThread: (() -> Unit) -> Unit,
  /** 还没算出来 / 算失败时用的 UA。 */
  private val fallback: String = USER_AGENT_PROFILES.getValue(UserAgentProfile.WEBVIEW),
) {

  @Volatile
  private var cached: String? = null

  /** 「已经排了一次队去主线程算」的去重旗。 */
  private val posted = AtomicBoolean(false)

  /**
   * 预热。**在主线程调**(`Ng2nApplication.onCreate`):第一发请求出去之前就把值算好,
   * 之后所有线程都是一次 `@Volatile` 读。
   *
   * 代价是冷启动的主线程上多了一次 WebView provider 初始化 —— 这是「永不让后台线程
   * 等主线程」的定价,票 19 真机裁决时一并量。不在主线程调的话退化成排队(见 [get])。
   */
  fun prewarm() {
    if (cached != null) return
    if (onMainThread()) evaluate() else scheduleOnMainThread()
  }

  /**
   * 现在这一发请求要用的 WebView UA。**任何线程都能调,任何线程都不会被挡住**。
   */
  fun get(): String {
    cached?.let { return it }
    // 主线程上求值是安全的:provider 的初始化任务就在这条线程上跑
    if (onMainThread()) return evaluate() ?: fallback
    // 后台线程:让主线程去算,这一发先用兜底 UA 发出去
    scheduleOnMainThread()
    return fallback
  }

  private fun scheduleOnMainThread() {
    if (!posted.compareAndSet(false, true)) return
    postToMainThread {
      try {
        evaluate()
      } finally {
        posted.set(false)
      }
    }
  }

  /** 求值一次。失败(provider 起不来、被厂商 ROM 阉了)就当没有,下次再说。 */
  private fun evaluate(): String? {
    val value = runCatching { readSystemUserAgent() }.getOrNull()?.takeIf { it.isNotBlank() }
    if (value != null) cached = value
    return value
  }
}
