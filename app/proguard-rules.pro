-keep class moe.shizuku.manager.adb.** { *; }

# Conscrypt 按类名与 JNI 加载实现，混淆后取不到 provider
-keep class org.conscrypt.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Ktor 通过服务加载器查找引擎与插件，相关类名要保留
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# BouncyCastle 的算法实现按名称反射查找
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
