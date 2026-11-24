# Godot JNI、插件注册和反射访问入口。
-keep class org.godotengine.godot.** { *; }
-keep class com.winter.godot.core.engine.ToolBridge { *; }
-keep class com.winter.godot.core.engine.OffscreenRenderView { *; }
-keepclasseswithmembernames class * { native <methods>; }
