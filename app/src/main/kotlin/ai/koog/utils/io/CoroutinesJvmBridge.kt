@file:JvmName("Coroutines_jvmKt")

package ai.koog.utils.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Koog 1.2.0 的 JVM-only OkHttp 后端引用此类名；Android actual 位于 Coroutines_androidKt。
@JvmName("getSuitableForIO")
fun koogJvmIoDispatcher(dispatchers: Dispatchers): CoroutineDispatcher = dispatchers.SuitableForIO
