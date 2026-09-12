package com.chasel.ng2n.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * `di` —— Hilt 装配。骨架期是空模块,存在的意义是证明 Hilt/KSP 管线通,
 * 并给后续票一个固定的落点(OkHttpClient、Json、Room、DataStore 都挂这里)。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
