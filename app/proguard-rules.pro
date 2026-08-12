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

# Keep our custom DataSource factory — referenced from a MediaSource built at
# runtime, which R8's static analysis cannot always see through.
-keep class com.hardplay.player.** { *; }
