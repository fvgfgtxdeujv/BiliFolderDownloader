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
