# Farside Workspace — release R8/ProGuard rules.
#
# Purpose: strip unreachable library code from the release APK. In particular this
# removes Bouncy Castle's EST (certificate enrollment) module, whose
# JcaJceUtils.getTrustAllTrustManager() is flagged by store security scanners even
# though nothing in the app or its dependency graph ever calls it.
#
# The app's JNI callbacks already carry @Keep; the rules below cover what R8 cannot
# trace on its own: JNI class lookups by binary name, Bouncy Castle's string-based
# algorithm registration, and SSHJ's reflective corners.

# --- JNI bridges: R8's default rules keep native methods, but C++ also looks these
# --- classes up by binary name (FindClass), so keep them whole.
-keep class com.farside.workspace.FreeRDPBridge { *; }
-keep class com.farside.workspace.VncBridge { *; }
-keep class com.farside.workspace.QuestCameraEncoder { *; }
-keep class com.farside.workspace.QuestAudioVerification { *; }
-keep class com.farside.workspace.DisplayCapturePermission { *; }

# --- Bouncy Castle: BouncyCastleProvider registers algorithms via string class names
# --- (put("...", "org.bouncycastle....")), invisible to R8's tracing. Keep the
# --- packages SSHJ and the app actually use. Deliberately omits est, tsp, tls, pqc,
# --- dvcs, mime and friends so they are stripped from the APK.
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.openssl.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.pkcs.** { *; }
-keep class org.bouncycastle.util.** { *; }
-dontwarn org.bouncycastle.**

# --- SSHJ: reflective corners; small library, keep whole for safety.
-keep class net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.**

# --- EdDSA (net.i2p): references JDK-internal sun.security.x509.X509Key, which
# --- does not exist on Android. The code path is never hit at runtime; suppress.
-dontwarn sun.security.x509.**

# --- Termux terminal emulator/view: custom views and JNI-adjacent code.
-keep class com.termux.** { *; }

# --- Serializable safety net.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
