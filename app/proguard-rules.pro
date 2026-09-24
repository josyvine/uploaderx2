# -------------------------------------------------------------
# UploaderX R8 / ProGuard Optimization Rules
# -------------------------------------------------------------

# 1. Keep JavaScript Interface methods so WebView can call them
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.vineyard.uploaderx.app.MainActivity$WebAppInterface {
    public *;
}

# 2. Keep Cryptographic Decryption classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# 3. Strip debug and verbose logging from release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# 4. Suppress warnings for AndroidX WebKit internals
-dontwarn androidx.webkit.**
-keep class androidx.webkit.** { *; }