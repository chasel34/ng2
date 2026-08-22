package com.chasel.ng2n.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * 传输层装配。
 *
 * **占位**:票 12(图片管线)需要「和协议层同一个 [OkHttpClient]」才能让附件域名带上
 * 登录态,但传输层是票 06 的活。所以这里先给一个裸 client 顶着 —— 图片侧只依赖
 * **注入进来的类型**,不依赖它是怎么造出来的。
 *
 * **TODO(票 06)**:反封锁链落地时把这个 provider 的**函数体**换掉(自管 CookieJar、
 * `X-User-Agent: Nga_Official`、UA、Referer、拦截器链、`renewTransport()` 真建新 client),
 * 签名与作用域保持不变,图片侧一行不动。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

  @Provides
  @Singleton
  fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}
