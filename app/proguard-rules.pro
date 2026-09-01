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
