##──────────────────────────────────────────────────────────────────────────##
##  Bori - Code – Release ProGuard / R8 rules                                   ##
##──────────────────────────────────────────────────────────────────────────##

# Keep line numbers in stack traces for crash reporting
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all annotations (required by Hilt, Room, Compose, serialization)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

##── Kotlin ─────────────────────────────────────────────────────────────────

-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { <fields>; }

##── Kotlinx Serialization ───────────────────────────────────────────────────

-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-dontwarn kotlinx.serialization.**

##── Kotlinx Coroutines ──────────────────────────────────────────────────────

-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

##── Hilt / Dagger ──────────────────────────────────────────────────────────
## KSP generates most Hilt keep rules automatically; these catch anything missed.

-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.EntryPoint class * { *; }
-keep class dagger.hilt.** { *; }
-dontwarn dagger.**
-dontwarn hilt_aggregated_deps.**

##── Room ────────────────────────────────────────────────────────────────────
## KSP generates most Room keep rules automatically; these are belt-and-suspenders.

-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { abstract *; }

##── Sora Code Editor ────────────────────────────────────────────────────────
## Sora uses internal reflection for syntax highlighting and rendering.

-keep class io.github.rosemoe.sora.** { *; }
-keepclassmembers class io.github.rosemoe.sora.** { *; }
-dontwarn io.github.rosemoe.sora.**

##── Bori Code Build Engine ────────────────────────────────────────────────────
## The build engine loads compiler tool classes via reflection and custom
## ClassLoaders at runtime. Keep the entire module to prevent R8 from
## removing or renaming invocation entry points.

-keep class com.bori.core.build.** { *; }
-keepclassmembers class com.bori.core.build.** { *; }

##── Security / Bouncycastle ─────────────────────────────────────────────────

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class org.openjsse.** { *; }
-dontwarn org.openjsse.**

##── OkHttp (transitive) ─────────────────────────────────────────────────────

-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okio.**
-keep class okio.** { *; }

##── Retrofit (transitive) ───────────────────────────────────────────────────

-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

##── Compose ─────────────────────────────────────────────────────────────────

-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
-dontwarn androidx.compose.**

##── Compose Markdown ────────────────────────────────────────────────────────

-dontwarn dev.jeziellago.**
-keep class dev.jeziellago.** { *; }

##── DataStore ───────────────────────────────────────────────────────────────

-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

##── Common Android patterns ─────────────────────────────────────────────────

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

##── Suppress noise from optional deps ───────────────────────────────────────

-dontwarn java.lang.instrument.**
-dontwarn sun.misc.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.**
-dontwarn org.codehaus.mojo.**

##── JGit / ECJ — JVM-only APIs not present on Android ──────────────────────

-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.lang.model.**
-dontwarn javax.management.**
-dontwarn javax.tools.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

##── LiteRT-LM (on-device Gemma) ───────────────────────────────────────────
## The native engine calls back into these classes over JNI and serializes tool
## definitions/messages with Gson, so their names and members must survive R8.

-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

##── MediaPipe LLM Inference (Gemma 2B local model) ─────────────────────────
## MediaPipe uses JNI, reflection, and native delegates. R8 must keep the
## full public API so native code can call back into the JVM at runtime.

-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

##── ECJ (Eclipse Java Compiler) ─────────────────────────────────────────────
## ECJ classes are loaded by the on-device build engine. Without these rules R8
## strips internal ECJ classes, causing ExceptionInInitializerError at runtime.

-keep class org.eclipse.jdt.** { *; }
-keepclassmembers class org.eclipse.jdt.** { *; }
-dontwarn org.eclipse.jdt.**
