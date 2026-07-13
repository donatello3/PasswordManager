# Keep Room entities and their fields (reflection)
-keep class io.kmanager.app.data.database.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep SQLCipher (native library)
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Keep Firebase classes
-keepattributes Signature
-keepattributes *Annotation*
-keepnames class com.google.firebase.** { *; }
-keepnames class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.gms.common.api.internal.IStatusCallback
-dontwarn com.google.firebase.**

# Keep GSON (reflection)
-keep class com.google.gson.** { *; }
-keep class io.kmanager.app.utils.** { *; }   # if you have custom GSON serializers

# Keep your data class (PasswordEntry) – all fields needed for JSON conversion
-keepclassmembers class io.kmanager.app.data.database.PasswordEntry {
    *;
}

# Keep all classes used by CryptoManager (encryption)
-keep class io.kmanager.app.utils.CryptoManager { *; }
-keep class io.kmanager.app.utils.EncryptionManager { *; }
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# Keep Biometric prompt callbacks (they are invoked by system)
-keep class androidx.biometric.** { *; }

# Keep coroutines and lifecycle (needed for Flow, LiveData)
-keep class kotlin.coroutines.** { *; }
-keep class androidx.lifecycle.** { *; }

# Keep WorkManager (if used for sync)
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Strip ALL Android Log calls in release.
# Note: e.printStackTrace() cannot be removed by ProGuard (it's a JVM method),
# so all logging must use Log.* to be stripped here.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}