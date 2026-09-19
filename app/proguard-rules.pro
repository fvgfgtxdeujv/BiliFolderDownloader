# Add project specific ProGuard rules here.
# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer {
    *** INSTANCE;
}
-keep,includedescriptorclasses class com.bilifolder.downloader.**$$serializer { *; }
-keepclassmembers class com.bilifolder.downloader.** {
    *** Companion;
}

# ---------- BouncyCastle ----------
# BC Provider 通过类名字符串注册算法（Cipher.getInstance("AES/GCM/NoPadding","BC")），
# R8 无法静态感知字符串引用的算法类，硬裁剪会导致运行时 NoClassDefFoundError，
# 因此整包保留（PQC 参数资源已通过 packaging.excludes 排除）。
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---------- Media3 / ExoPlayer ----------
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ---------- ZXing ----------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---------- OkHttp ----------
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------- Tinker ----------
# TinkerApplication 的 delegate（BiliAppLike）类名以字符串传给 loader 反射实例化，
# 混淆/裁剪会破坏反射，必须整类保留。BiliApp 因 manifest 引用不会被移除，但构造入口同保。
-keep class com.bilifolder.downloader.BiliAppLike { *; }
-keep class com.bilifolder.downloader.BiliApp { *; }

# ---------- Gopeed 引擎（gomobile 绑定） ----------
# gomobile 生成的绑定类通过 JNI 反射注册回调代理与入口方法，R8 无法静态感知，
# 重命名/裁剪会导致 UnsatisfiedLinkError 或回调丢失，必须整包保留。
-keep class com.gopeed.** { *; }
