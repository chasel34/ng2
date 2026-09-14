package com.chasel.ng2n.di

import com.chasel.ng2n.data.net.TopicCachePayloadReader
import com.chasel.ng2n.data.topic.TopicSnapshotSink
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComputeDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object TopicModule {

  @Provides
  @Singleton
  fun provideTopicSnapshotSink(reader: TopicCachePayloadReader): TopicSnapshotSink =
    TopicSnapshotSink { snapshot -> reader.save(snapshot) }

  @Provides
  @ComputeDispatcher
  fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default

  @Provides
  @IoDispatcher
  fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
