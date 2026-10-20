# JNI: native methods are looked up by name from libvlm-ai.so
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.adityabalagere.photosorter.VlmAi { *; }

# MediaPipe tasks use reflection / protobuf-lite internally
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**

# OkHttp ships its own consumer rules; silence optional platform deps
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
