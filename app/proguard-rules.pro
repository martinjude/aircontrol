# MediaPipe calls back into Java from JNI and builds protos reflectively: keep it whole.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
# Flogger (MediaPipe's logger) finds its caller by walking the stack: names must stay intact.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# Keep stack traces readable for bug reports.
-keepattributes SourceFile,LineNumberTable
