# ONNX Runtime 通过 JNI 反射调用 Java 侧类，混淆后必然崩。
# 目前 release 未开混淆（isMinifyEnabled = false），这里先把规则准备好。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
