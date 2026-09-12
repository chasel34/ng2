package com.chasel.ng2n.data.account

import android.webkit.WebSettings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chasel.ng2n.core.net.InMemoryComboCache
import com.chasel.ng2n.core.net.NetworkSettingsSource
import com.chasel.ng2n.core.net.NgaClient
import com.chasel.ng2n.core.net.NgaRequest
import com.chasel.ng2n.core.net.Operation
import com.chasel.ng2n.core.net.UserAgents
import com.chasel.ng2n.core.net.queryOf
import com.chasel.ng2n.data.net.OkHttpTransportFactory
import com.chasel.ng2n.data.net.ngaHttpClientBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

/**
 * **登录态冒烟** —— 拿设备上那份真凭证打一次只有登录用户才拿得到的接口。
 *
 * ## 默认跳过(与票 06 的 `NgaIntegrationSmokeTest` 同纪律)
 *
 * 铁律是「禁止用跳过来变绿」,联网冒烟是唯一例外:它的失败可能与代码无关
 * (没网、NGA 在限流、设备上还没登录)。门控是**运行时参数**,打开就真跑。
 *
 * ## 怎么跑(需要所有者先在模拟器里登录一次)
 *
 * ```bash
 * cd native
 * # 1) 装包、在 app 里走一次 WebView 登录(见票 15 Comments 的「待所有者」)
 * # 2) 模拟器出网要走代理:adb shell settings put global http_proxy 10.0.2.2:7897
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.nga_integration=1 \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.chasel.ng2n.data.account.LoggedInSmokeTest
 * ```
 *
 * ## 为什么是 androidTest 而不是 JVM 单测
 *
 * 凭证在设备上:DataStore 文件 + Android Keystore 里的密钥,**离开这台设备解不开**
 * (也正因如此,cid 不需要以任何形式出现在仓库或环境变量里 —— 票面「不要猜凭证」)。
 * 所以这条冒烟必须在 app 自己的进程里跑。
 *
 * ## 纪律
 *
 * - 只读、只打一次(`nuke.php?__lib=noti&__act=get_all`,API 文档 §9.1);
 * - **只断言形状**:响应 `data["0"]` 里有 `unread` —— 游客拿不到这个结构;
 * - 不打印任何凭证(P1-04),只打 uid 与走通的组合。
 * - **不要连续打**:NGA 会因背靠背冷启动限流,两次之间静置 ≥60s。
 */
@RunWith(AndroidJUnit4::class)
class LoggedInSmokeTest {

  private fun enabled(): Boolean =
    InstrumentationRegistry.getArguments().getString("nga_integration") == "1"

  private fun accountStore(): AccountStore {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return AccountStore.withKeystore(
      PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.preferencesDataStoreFile(AccountStore.FILE_NAME) },
      ),
    )
  }

  private fun client(store: AccountStore): NgaClient {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return NgaClient(
      transports = OkHttpTransportFactory(ngaHttpClientBuilder().build()),
      // 凭证走生产那条路:每请求现读 AccountStore
      credentials = store,
      settings = NetworkSettingsSource.defaults(),
      userAgents = UserAgents { WebSettings.getDefaultUserAgent(context) },
      comboCache = InMemoryComboCache(),
      readChain = NgaClient.defaultReadChain(listCredentials = { store.all() }),
    )
  }

  @Test
  fun 登录态拉一次通知_拿得到只有登录用户才有的结构() = runTest {
    assumeTrue(
      "默认跳过;要跑请加 -Pandroid.testInstrumentationRunnerArguments.nga_integration=1",
      enabled(),
    )
    val store = accountStore()
    val account = store.currentAccount()
    assumeTrue("设备上还没有登录账号 —— 先在 app 里走一次 WebView 登录", account != null)

    val result = client(store).execute(
      NgaRequest(
        path = "nuke.php",
        operation = Operation.READ,
        query = queryOf("__lib" to "noti", "__act" to "get_all"),
      ),
    )

    val box = (result.data as? JsonObject)?.get("0") as? JsonObject
    // JUnit 的 assertTrue 是 (message, condition),与 kotlin.test 反着来
    assertTrue(
      "登录态才拿得到的通知结构没出现;顶层键=${(result.data as? JsonObject)?.keys}",
      box != null && box.containsKey("unread"),
    )
    // 只打 uid 与走通的组合,绝不打 cid(P1-04)
    println("[integration] uid=${account!!.uid} via=${result.via} keys=${box!!.keys}")
  }
}
