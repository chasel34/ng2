# 骨架期的最小规则集。kotlinx.serialization / Hilt / OkHttp / Coil 都自带 consumer rules,
# 这里只放它们盖不到的部分;各票引入新库时按需追加,并在票的 Comments 里记原因。

# 崩溃栈可读(自用 app,体积不敏感)。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization:@Serializable 生成的 Companion.serializer() 反射入口。
# (插件自带规则已覆盖绝大多数场景,这条是兜底。)
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
