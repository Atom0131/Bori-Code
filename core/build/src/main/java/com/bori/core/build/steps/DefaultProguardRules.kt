package com.bori.core.build.steps

internal object DefaultProguardRules {

    val RULES = """
        # ── Android components ────────────────────────────────────────────────
        -keep public class * extends android.app.Activity
        -keep public class * extends android.app.Application
        -keep public class * extends android.app.Service
        -keep public class * extends android.content.BroadcastReceiver
        -keep public class * extends android.content.ContentProvider

        # ── XML-inflated Views ────────────────────────────────────────────────
        -keep public class * extends android.view.View {
            public <init>(android.content.Context);
            public <init>(android.content.Context, android.util.AttributeSet);
            public <init>(android.content.Context, android.util.AttributeSet, int);
        }

        # ── Parcelable ────────────────────────────────────────────────────────
        -keep class * implements android.os.Parcelable {
            public static final android.os.Parcelable${'$'}Creator *;
        }

        # ── Serializable ──────────────────────────────────────────────────────
        -keepclassmembers class * implements java.io.Serializable {
            static final long serialVersionUID;
            private static final java.io.ObjectStreamField[] serialPersistentFields;
            private void writeObject(java.io.ObjectOutputStream);
            private void readObject(java.io.ObjectInputStream);
            java.lang.Object writeReplace();
            java.lang.Object readResolve();
        }

        # ── Enums ─────────────────────────────────────────────────────────────
        -keepclassmembers enum * {
            public static **[] values();
            public static ** valueOf(java.lang.String);
        }

        # ── Native methods ────────────────────────────────────────────────────
        -keepclasseswithmembernames class * {
            native <methods>;
        }

        # ── Missing classes ───────────────────────────────────────────────────
        # Compile-time annotations and optional dependencies that libraries reference but
        # apps don't ship. AGP makes you list these; on the phone a release build would just
        # fail, so warnings about missing classes are ignored.
        -dontwarn org.jetbrains.annotations.**
        -dontwarn javax.annotation.**
        -dontwarn org.conscrypt.**
        -dontwarn org.bouncycastle.**
        -dontwarn org.openjsse.**
        -dontwarn kotlin.**
        -ignorewarnings

        # ── Annotations + debug info ──────────────────────────────────────────
        -keepattributes *Annotation*
        -keepattributes SourceFile,LineNumberTable
        -renamesourcefileattribute SourceFile
    """.trimIndent()
}
