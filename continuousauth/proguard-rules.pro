# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# =========================================================================
# Security Package — Android Keystore & Crypto Hardening
# =========================================================================

# Keep Keystore interaction classes — required for Android Keystore provider
# reflection-based access. Obfuscating these breaks key generation/retrieval.
-keep class com.ca.continuousauth.security.SecureKeyManager { *; }
-keep class com.ca.continuousauth.security.SecureModelStorage { *; }
-keep class com.ca.continuousauth.security.IntegrityVerifier { *; }

# Obfuscate internal/private members of security classes to resist reverse engineering
-keepclassmembers class com.ca.continuousauth.security.** {
    !public <methods>;
    !public <fields>;
}

# Strip debug/info logging in release builds to prevent leaking
# encryption metadata, key aliases, and file paths
-assumenosideeffects class com.ca.continuousauth.utils.Logger {
    public static void d(...);
}

# Preserve Java serialization used for checkpoint HashMap and vector List
-keepattributes Signature
-keep class * implements java.io.Serializable { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}