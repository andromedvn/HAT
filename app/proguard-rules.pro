# Proguard rules for HAT
#
# The release build sets isMinifyEnabled = true. These rules are not optional:
# without them R8 strips members that are only reached reflectively, and the
# failure appears at runtime as a serialization error or a Worker that never
# runs, not at build time.

# --- kotlinx.serialization -------------------------------------------------
# HAT stores UserSettings as JSON via kotlinx.serialization, so the generated
# serializers must survive shrinking.

# Keep the Companion object of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep serializer() on named companion objects.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep INSTANCE.serializer() for serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The serialized field names are part of the on-disk format. Renaming them
# would silently reset every user's settings on upgrade.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault

# Enum constants are serialized by name; renaming one changes the stored value.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- WorkManager -----------------------------------------------------------
# ArchiveSyncWorker is constructed by name from the scheduled work spec, so
# R8 must not rename or remove it. (androidx.work ships consumer rules, but
# HAT depends on the worker surviving in a minified release, so state it.)
-keep class * extends androidx.work.ListenableWorker { *; }

# --- ViewModels ------------------------------------------------------------
# ViewModelProvider.Factory instantiates these reflectively.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# --- Data layer ------------------------------------------------------------
# ThemeType / SortType / TimeRangeLabel round-trip through the settings JSON.
-keep class andromedvn.heuristic.activity.tracker.data.** { *; }

# --- Diagnostics -----------------------------------------------------------
# Crash logs are written by name for later inspection.
-keep class andromedvn.heuristic.activity.tracker.utils.HatLogger { *; }

# AndroidX and Compose ship their own consumer rules; Jetbrains annotations
# are referenced but not always packaged.
-dontwarn org.jetbrains.annotations.**
