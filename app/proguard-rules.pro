-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留 kotlinx.serialization 的反射序列化入口。
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
