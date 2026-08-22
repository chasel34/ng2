package com.chasel.ng2n

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.chasel.ng2n.data.StorageBootstrap
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class Ng2nApplication : Application() {

  @Inject
  lateinit var storageBootstrap: StorageBootstrap

  override fun onCreate() {
    // StrictMode 要在 Hilt 注入(super.onCreate)之前装上,不然装配本身的违规看不见
    installStrictMode()
    super.onCreate()
    // 存储层的预热:全在 IO 协程里,首屏不等它(修 P2-04)
    storageBootstrap.start()
  }

  /**
   * **修 P2-04 的验证工具**:debug 变体开 StrictMode,主线程一碰磁盘就往 logcat 报警。
   *
   * 审计里 RN 版的病灶是「模块初始化时同步 `openDatabaseSync` + 全表扫描」——
   * 这一版所有 load 都走 `Dispatchers.IO` 的协程,首屏不等任何一份数据。
   * 光靠纪律守不住,所以让运行时钉死它。
   *
   * 判定条件用 `FLAG_DEBUGGABLE` 而不是 `BuildConfig.DEBUG`:本工程 release 也用 debug
   * keystore 签名(为了小米真机 `install -r` 保登录态),但 release 变体不是 debuggable,
   * 这个判据仍然精确,而且**不必为它打开 `buildConfig` 特性**。
   *
   * [PENALTY_DEATH] 默认关:开着的话任何一次主线程磁盘读都直接崩,
   * 排查第三方库(Coil / WebView / Room 首次建库)时会很吵。真要抓现行时手动翻开。
   *
   * 验证方法与结果见票 14 的 Comments。
   */
  private fun installStrictMode() {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return

    val thread = StrictMode.ThreadPolicy.Builder()
      .detectDiskReads()
      .detectDiskWrites()
      .detectNetwork()
      .detectCustomSlowCalls()
      .penaltyLog()
      .apply { if (PENALTY_DEATH) penaltyDeath() }
      .build()
    StrictMode.setThreadPolicy(thread)

    StrictMode.setVmPolicy(
      StrictMode.VmPolicy.Builder()
        .detectLeakedSqlLiteObjects()
        .detectLeakedClosableObjects()
        .penaltyLog()
        .build(),
    )
  }

  private companion object {
    /**
     * 翻开就是「主线程一碰磁盘/网络直接崩进程」(StrictMode 的惩罚是**整条 ThreadPolicy**
     * 共用的,不能只对 diskReads 单开)。默认关 —— 打开是为了抓现行,
     * 平时开着会被第三方库的正常初始化吵到没法用。
     */
    const val PENALTY_DEATH = false
  }
}
