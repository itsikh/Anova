# Add project specific ProGuard rules here.

# Suppress R8 warnings for missing errorprone annotations (compile-only, not needed at runtime)
-dontwarn com.google.errorprone.annotations.**

# Keep CredentialManager / GoogleIdTokenCredential data classes
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
