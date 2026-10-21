# sherpa-onnx: the native code reads these Kotlin classes and fields by name
-keep class com.k2fsa.sherpa.onnx.** { *; }
# methods the avatar page calls (AndroidBridge.touch / ready / error)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-dontwarn com.k2fsa.sherpa.onnx.**
