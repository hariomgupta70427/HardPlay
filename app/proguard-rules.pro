# ---------------------------------------------------------------------------
# TDLib — JNI resolves these by name at runtime, so R8 must not touch them.
# TdApi's nested classes are also constructed reflectively by the JNI layer.
# ---------------------------------------------------------------------------
-keep class org.drinkless.tdlib.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ---------------------------------------------------------------------------
# Room / Hilt / Media3 are all well-behaved with the consumer rules they ship,
# so the only extras needed are for our own reflective surfaces.
# ---------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Kotlin coroutines debug agent is stripped at build time already.
-dontwarn kotlinx.coroutines.debug.**

# Media3 extractors are looked up by class name in a few code paths.
-keep class androidx.media3.extractor.** { *; }

# Keep our custom DataSource and the MediaDataSource used for frame extraction —
# both are referenced from objects built at runtime (a MediaSource, and
# MediaMetadataRetriever's native side), which R8's static analysis cannot always
# see through. Note the package: this rule read `com.hardplay.player.**` until it
# was noticed that no such package exists, so it had been protecting nothing.
-keep class com.hardplay.playback.** { *; }
