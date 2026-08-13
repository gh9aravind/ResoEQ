# Tunex ProGuard Rules
# Optimized for smaller APK size

# ======== General Android Rules ========
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ======== Kotlin ========
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tunex.**$$serializer { *; }
-keepclassmembers class com.tunex.** {
    *** Companion;
}
-keepclasseswithmembers class com.tunex.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ======== Compose ========
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ======== Data Models - Keep for serialization ========
-keep class com.tunex.data.model.** { *; }
-keepclassmembers class com.tunex.data.model.** { *; }

# ======== Audio Engine ========
-keep class com.tunex.audio.** { *; }
-keepclassmembers class com.tunex.audio.** { *; }

# ======== Service & Receivers ========
-keep class com.tunex.audio.service.AudioProcessingService { *; }
-keep class com.tunex.receiver.** { *; }

# ======== ViewModel ========
-keep class com.tunex.ui.viewmodel.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ======== Gson ========
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# ======== DataStore ========
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ======== Enum classes ========
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ======== Parcelable ========
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ======== R8 Optimization ========
-allowaccessmodification
-repackageclasses ''

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ======== Accompanist ========
-dontwarn com.google.accompanist.**

# ======== Navigation ========
-keep class androidx.navigation.** { *; }

# ======== Coroutines ========
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**