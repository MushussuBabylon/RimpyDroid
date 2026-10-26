# Minification is disabled in app/build.gradle.kts, so these rules are only a safety net
# in case someone turns it on later.
-keep class in.dragonbra.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.github.luben.zstd.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn **
