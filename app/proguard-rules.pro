# Add project specific ProGuard rules here.

# Suppress R8 warnings for missing errorprone annotations (compile-only, not needed at runtime)
-dontwarn com.google.errorprone.annotations.**

# Keep CredentialManager / GoogleIdTokenCredential data classes
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# ── Gson ──────────────────────────────────────────────────────────────────────
# R8 renames Kotlin data classes; Gson uses reflection to instantiate them and
# read @SerializedName fields — the rename breaks both. Keep the full cloud
# model package (all Firebase/Anova REST + WebSocket DTOs) so Gson can work.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.template.app.anova.cloud.** { *; }
# Keep @SerializedName field names in any class (belt-and-suspenders)
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Required for Gson generic TypeToken usage with R8 3.0+
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
