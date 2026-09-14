package com.chasel.ng2n.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AiImageReader {
  private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
    .callTimeout(45, TimeUnit.SECONDS).build()

  suspend fun read(url: String): String {
    val bytes = suspendCancellableCoroutine { continuation ->
      val call = client.newCall(Request.Builder().url(url).build())
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
          try {
            val data = response.use {
              check(it.isSuccessful) { "图片读取失败" }
              val body = checkNotNull(it.body)
              check(body.contentLength() <= MAX_BYTES) { "图片过大" }
              body.byteStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                  val count = input.read(buffer)
                  if (count < 0) break
                  check(output.size() + count <= MAX_BYTES) { "图片过大" }
                  output.write(buffer, 0, count)
                }
                output.toByteArray()
              }
            }
            if (continuation.isActive) continuation.resume(data)
          } catch (cause: Exception) { if (continuation.isActive) continuation.resumeWithException(cause) }
        }
      })
    }
    return withContext(Dispatchers.Default) {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法解码图片" }
      var sample = 1
      while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
      val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }))
      try {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
        "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
      } finally { bitmap.recycle() }
    }
  }
  private const val MAX_BYTES = 12 * 1024 * 1024
}
