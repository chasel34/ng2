package com.chasel.ng2n.di

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.chasel.ng2n.data.account.AccountCrypto
import com.chasel.ng2n.data.account.AccountStoreLog
import com.chasel.ng2n.data.account.KeystoreCrypto
import com.chasel.ng2n.data.ai.settings.AiKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiSettingsModule {
  @Provides
  @Singleton
  @AiKeyPreferences
  fun provideKeyPreferences(
    @ApplicationContext context: Context,
    @IoScope scope: CoroutineScope,
  ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler {
      mutablePreferencesOf(AiKeyStore.KEY to "unreadable")
    },
    scope = scope,
    produceFile = { context.preferencesDataStoreFile(AiKeyStore.FILE_NAME) },
  )

  @Provides
  @Singleton
  @AiKeyCrypto
  fun provideKeyCrypto(): AccountCrypto =
    KeystoreCrypto(alias = AiKeyStore.KEY_ALIAS, log = AccountStoreLog.NONE)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiKeyPreferences

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AiKeyCrypto
