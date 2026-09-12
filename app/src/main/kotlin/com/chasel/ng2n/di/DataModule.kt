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
import com.chasel.ng2n.data.account.AccountCrypto
import com.chasel.ng2n.data.account.AccountStore
import com.chasel.ng2n.data.account.AccountStoreLog
import com.chasel.ng2n.data.account.AndroidAccountStoreLog
import com.chasel.ng2n.data.account.AndroidWebCookieVault
import com.chasel.ng2n.data.account.KeystoreCrypto
import com.chasel.ng2n.data.account.WebCookieVault
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
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

  @Provides
  @Singleton
  @IoScope
  fun provideIoScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Provides
  @Singleton
  fun provideDatabase(@ApplicationContext context: Context): Ng2nDatabase =
    Room.databaseBuilder(context, Ng2nDatabase::class.java, Ng2nDatabase.NAME)
      .fallbackToDestructiveMigration(dropAllTables = true)
      .setQueryCoroutineContext(Dispatchers.IO)
      .build()

  @Provides
  fun provideBrowseHistoryDao(db: Ng2nDatabase): BrowseHistoryDao = db.browseHistoryDao()

  @Provides
  fun provideTopicCacheDao(db: Ng2nDatabase): TopicCacheDao = db.topicCacheDao()

  @Provides
  fun provideNotificationReadDao(db: Ng2nDatabase): NotificationReadDao = db.notificationReadDao()

  @Provides
  @Singleton
  @SettingsPreferences
  fun provideSettingsDataStore(
    @ApplicationContext context: Context,
    @IoScope scope: CoroutineScope,
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = {
      context.preferencesDataStoreFile(com.chasel.ng2n.data.settings.SettingsStore.FILE_NAME)
    },
  )

  @Provides
  @Singleton
  @AccountPreferences
  fun provideAccountDataStore(
    @ApplicationContext context: Context,
    @IoScope scope: CoroutineScope,
    log: AccountStoreLog,
  ): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(AccountStore.FILE_NAME)
    return PreferenceDataStoreFactory.create(
      corruptionHandler = ReplaceFileCorruptionHandler { cause ->
        runCatching { file.copyTo(File("${file.path}.corrupt"), overwrite = true) }
        log.warn("凭证文件损坏,已另存 ${file.name}.corrupt 后重建为空表", cause)
        emptyPreferences()
      },
      scope = scope,
      produceFile = { file },
    )
  }

  @Provides
  @Singleton
  fun provideAccountCrypto(): AccountCrypto = KeystoreCrypto()

  @Provides
  @Singleton
  fun provideAccountStoreLog(): AccountStoreLog = AndroidAccountStoreLog()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialModule {

  @Binds
  abstract fun bindCredentialSource(store: AccountStore): CredentialSource

  @Binds
  abstract fun bindWebCookieVault(vault: AndroidWebCookieVault): WebCookieVault
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
