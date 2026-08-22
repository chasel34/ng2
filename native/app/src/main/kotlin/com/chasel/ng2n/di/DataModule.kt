package com.chasel.ng2n.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.chasel.ng2n.core.net.CredentialSource
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.db.BrowseHistoryDao
import com.chasel.ng2n.data.db.Ng2nDatabase
import com.chasel.ng2n.data.db.NotificationReadDao
import com.chasel.ng2n.data.db.TopicCacheDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 存储层的 Hilt 装配(票 14)。
 *
 * ## 修 P2-04:冷启动路径零同步磁盘 IO
 *
 * - Room 库**懒建连接**:`Room.databaseBuilder(...).build()` 不碰磁盘,
 *   第一次真正查询才打开文件 —— 而 DAO 全是 suspend/Flow,只可能在协程里查;
 * - `setQueryCoroutineContext(Dispatchers.IO)` 把 Room 自己的读写钉在 IO 上;
 * - DataStore 本来就只有 suspend/Flow,并且跑在 [IoScope] 上;
 * - 所有 `warmUp()` 由调用方在 IO 协程里发起,**首屏不等**。
 *
 * 这条纪律由 `Ng2nApplication` 的 StrictMode 在 debug 变体上钉死(违规即 logcat 报警)。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

  /** 全 app 一个的后台 scope:DataStore 的读写协程与仓库的批刷定时器都挂它。 */
  @Provides
  @Singleton
  @IoScope
  fun provideIoScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): Ng2nDatabase =
    Room.databaseBuilder(context, Ng2nDatabase::class.java, Ng2nDatabase.NAME)
      // 无迁移机制,照抄 RN 版策略:「换结构就换表名,老数据作废」。
      // 三张表全是可重建的本机数据,重建代价远小于维护一条迁移链(见 Ng2nDatabase 的注释)。
      .fallbackToDestructiveMigration(dropAllTables = true)
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()

  @Provides
  fun provideBrowseHistoryDao(db: Ng2nDatabase): BrowseHistoryDao = db.browseHistoryDao()

  @Provides
  fun provideTopicCacheDao(db: Ng2nDatabase): TopicCacheDao = db.topicCacheDao()

  @Provides
  fun provideNotificationReadDao(db: Ng2nDatabase): NotificationReadDao = db.notificationReadDao()

  /** 设置、屏蔽规则、搜索历史、版块树、公告、签到、收藏索引、诊断日志都住这一个文件。 */
  @Provides
  @Singleton
  @SettingsPreferences
  fun provideSettingsDataStore(
    @ApplicationContext context: Context,
    @IoScope scope: CoroutineScope,
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    // 文件损坏(断电写一半)时当空的重来 —— 设置丢了回默认值,总好过起不来
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = {
      context.preferencesDataStoreFile(com.chasel.ng2n.data.settings.SettingsStore.FILE_NAME)
    },
  )

  /**
   * 凭证单独一个文件:载荷是 Keystore 加密过的,读写频率与生命周期都跟设置不一样,
   * 混在一起意味着每改一次字号都要重写一遍加密的账号表。
   */
  @Provides
  @Singleton
  @AccountPreferences
  fun provideAccountDataStore(
    @ApplicationContext context: Context,
    @IoScope scope: CoroutineScope,
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = { context.preferencesDataStoreFile(AccountStore.FILE_NAME) },
  )
}

/**
 * core 层(票 06 的反封锁链)只认 [CredentialSource] 这个纯 Kotlin 接口,
 * 不认 DataStore / Keystore。绑定在这里。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialModule {

  @Binds
  abstract fun bindCredentialSource(store: AccountStore): CredentialSource
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AccountPreferences
