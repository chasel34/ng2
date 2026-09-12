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
    assertTrue(
      "登录态才拿得到的通知结构没出现;顶层键=${(result.data as? JsonObject)?.keys}",
      box != null && box.containsKey("unread"),
    )
    println("[integration] uid=${account!!.uid} via=${result.via} keys=${box!!.keys}")
  }
}
