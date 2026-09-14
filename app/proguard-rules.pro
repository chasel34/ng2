-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留 kotlinx.serialization 的反射序列化入口。
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 无界面调用者时仍保留 AI 适配入口，使 release 验证实际覆盖框架运行路径。
-keep class com.chasel.ng2n.data.ai.KoogAgentFactory { public *; }
-keep class com.chasel.ng2n.data.ai.DeepSeekClientFactory { public *; }

# Ktor 的可选桌面调试器探测捕获 Throwable；Android 缺少 JMX 时返回 false。
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
