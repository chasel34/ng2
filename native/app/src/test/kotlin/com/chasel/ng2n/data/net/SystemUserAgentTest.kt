package com.chasel.ng2n.data.net

import com.chasel.ng2n.core.net.USER_AGENT_PROFILES
import com.chasel.ng2n.core.net.UserAgentProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 票 35 的死锁那一半:**取系统 WebView UA 不许在非主线程上等主线程**。
 *
 * 现场是 `WebSettings.getDefaultUserAgent()` 在后台线程里 `CountDownLatch.await()` 等主线程,
 * 而它又被包在 `SynchronizedLazyImpl` 里 —— 求值期间锁被攥着,主线程随后来求同一把锁,
 * 两边互等 → ANR(完整线程栈在票 35)。这里用一个**永远不会被放行的闩**复刻那半边:
 * 真实实现在这种情况下会把调用线程挂死,[SystemUserAgent] 必须立刻返回兜底 UA。
 *
 * `android.webkit` / `Looper` 都进不了 JVM 单测,所以「在不在主线程」「怎么把活儿丢给主线程」
 * 都是构造参数,这里换成假的(生产装配见 `di/NetworkModule.kt`)。
 */
class SystemUserAgentTest {

  private val fallback = USER_AGENT_PROFILES.getValue(UserAgentProfile.WEBVIEW)

  private val deviceUserAgent =
    "Mozilla/5.0 (Linux; Android 16; Pixel 8 Build/BP31.250610.004) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.60 Mobile Safari/537.36"

  /** 攒下「丢给主线程」的活儿,由测试自己决定什么时候跑 —— 相当于主线程的 looper。 */
  private class FakeMainLooper {
    val tasks = mutableListOf<() -> Unit>()

    fun post(task: () -> Unit) {
      synchronized(tasks) { tasks += task }
    }

    fun drain() {
      val pending = synchronized(tasks) { tasks.toList().also { tasks.clear() } }
      pending.forEach { it() }
    }

    val size: Int get() = synchronized(tasks) { tasks.size }
  }

  @Test
  fun `非主线程读 —— 系统调用会挂死时也立刻拿到兜底 UA`() {
    val stuck = CountDownLatch(1)
    val looper = FakeMainLooper()
    val agent = SystemUserAgent(
      onMainThread = { false },
      readSystemUserAgent = {
        // WebView provider 没起来时 getDefaultUserAgent 就是这么等主线程的
        stuck.await()
        deviceUserAgent
      },
      postToMainThread = looper::post,
    )

    val done = CountDownLatch(1)
    var value: String? = null
    val worker = Thread {
      value = agent.get()
      done.countDown()
    }
    worker.start()

    // 旧实现(lazy 内调用)会在这里超时 —— 那正是登录态冷启动 ANR 的那一半
    assertTrue(done.await(5, TimeUnit.SECONDS), "get() 在非主线程上被挡住了")
    assertEquals(fallback, value)
    // 而且它把求值排给了主线程,下一发就能用上真值
    assertEquals(1, looper.size)
    stuck.countDown()
    worker.join(5_000)
  }

  @Test
  fun `主线程读 —— 就地求值并记下来,之后所有线程都是现成值`() {
    var reads = 0
    val looper = FakeMainLooper()
    val agent = SystemUserAgent(
      onMainThread = { true },
      readSystemUserAgent = { reads += 1; deviceUserAgent },
      postToMainThread = looper::post,
    )

    assertEquals(deviceUserAgent, agent.get())
    assertEquals(deviceUserAgent, agent.get())
    assertEquals(1, reads, "求过一次就该记住")
    assertEquals(0, looper.size, "主线程上不必再往主线程排队")
  }

  @Test
  fun `预热之后后台线程直接读到真值,不再排队`() {
    val looper = FakeMainLooper()
    var mainThread = true
    val agent = SystemUserAgent(
      onMainThread = { mainThread },
      readSystemUserAgent = { deviceUserAgent },
      postToMainThread = looper::post,
    )

    agent.prewarm() // Application.onCreate,主线程
    mainThread = false

    assertEquals(deviceUserAgent, agent.get())
    assertEquals(0, looper.size)
  }

  @Test
  fun `排给主线程的那一发跑完之后,后台线程读到的就是真值`() {
    val looper = FakeMainLooper()
    val agent = SystemUserAgent(
      onMainThread = { false },
      readSystemUserAgent = { deviceUserAgent },
      postToMainThread = looper::post,
    )

    assertEquals(fallback, agent.get())
    looper.drain() // 主线程把 provider 拉起来了
    assertEquals(deviceUserAgent, agent.get())
  }

  @Test
  fun `求值抛异常 —— 退回兜底 UA,不往上炸`() {
    val looper = FakeMainLooper()
    val agent = SystemUserAgent(
      onMainThread = { true },
      readSystemUserAgent = { throw IllegalStateException("WebView provider 起不来") },
      postToMainThread = looper::post,
    )

    assertEquals(fallback, agent.get())
  }

  @Test
  fun `求到空串当没求到 —— 兜底 UA 至少是条像样的浏览器 UA`() {
    val looper = FakeMainLooper()
    val agent = SystemUserAgent(
      onMainThread = { true },
      readSystemUserAgent = { "   " },
      postToMainThread = looper::post,
    )

    assertEquals(fallback, agent.get())
    assertTrue(fallback.startsWith("Mozilla/5.0"), "兜底值必须仍是 webview 档的语义")
  }

  @Test
  fun `兜底值就是 RN 版 webview 档那一个常量`() {
    val looper = FakeMainLooper()
    val agent = SystemUserAgent(
      onMainThread = { false },
      readSystemUserAgent = { deviceUserAgent },
      postToMainThread = looper::post,
    )

    assertSame(USER_AGENT_PROFILES.getValue(UserAgentProfile.WEBVIEW), agent.get())
  }

  @Test
  fun `多个后台线程同时读 —— 都不阻塞,只往主线程排一次队`() {
    val stuck = CountDownLatch(1)
    val looper = FakeMainLooper()
    val agent = SystemUserAgent(
      onMainThread = { false },
      readSystemUserAgent = { stuck.await(); deviceUserAgent },
      postToMainThread = looper::post,
    )

    val done = CountDownLatch(8)
    val values = java.util.Collections.synchronizedList(mutableListOf<String>())
    repeat(8) {
      Thread {
        values += agent.get()
        done.countDown()
      }.start()
    }

    assertTrue(done.await(5, TimeUnit.SECONDS), "并发读被挡住了")
    assertEquals(8, values.size)
    assertTrue(values.all { it == fallback })
    assertEquals(1, looper.size, "去重旗没起作用,重复往主线程排队")
    stuck.countDown()
  }

  @Test
  fun `预热在非主线程调 —— 不自己求值,改成排给主线程`() {
    val looper = FakeMainLooper()
    var read = false
    val agent = SystemUserAgent(
      onMainThread = { false },
      readSystemUserAgent = { read = true; deviceUserAgent },
      postToMainThread = looper::post,
    )

    agent.prewarm()

    assertFalse(read, "非主线程上不许自己去问系统要 UA")
    assertEquals(1, looper.size)
  }

  @Test
  fun `一次失败之后还能再排一次队 —— 去重旗不会卡死`() {
    val looper = FakeMainLooper()
    var attempts = 0
    val agent = SystemUserAgent(
      onMainThread = { false },
      readSystemUserAgent = {
        attempts += 1
        if (attempts == 1) throw IllegalStateException("provider 还没准备好") else deviceUserAgent
      },
      postToMainThread = looper::post,
    )

    assertEquals(fallback, agent.get())
    looper.drain() // 第一次求值失败
    assertEquals(fallback, agent.get())
    looper.drain() // 第二次成功
    assertEquals(deviceUserAgent, agent.get())
    assertEquals(2, attempts)
  }

  @Test
  fun `还没求到值时不写缓存 —— 兜底值不会被当成真值记住`() {
    val looper = FakeMainLooper()
    var current: String? = null
    val agent = SystemUserAgent(
      onMainThread = { true },
      readSystemUserAgent = { current ?: throw IllegalStateException("还没有") },
      postToMainThread = looper::post,
    )

    assertEquals(fallback, agent.get())
    assertNull(current)
    current = deviceUserAgent
    assertEquals(deviceUserAgent, agent.get(), "上一次的兜底值不该把真值挡住")
  }
}
