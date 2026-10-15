# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.griipbuddy.message.** { *** Companion; }
-keepclasseswithmembers class com.griipbuddy.message.** { kotlinx.serialization.KSerializer serializer(...); }
# BouncyCastle HPKE / Ed25519 (reflection-free, but keep providers intact)
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# SQLCipher
-keep class net.zetetic.database.** { *; }
# LiveKit / WebRTC
-keep class livekit.org.webrtc.** { *; }
-keep class io.livekit.android.** { *; }
-dontwarn javax.naming.**
