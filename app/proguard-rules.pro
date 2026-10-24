# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep MPV JNI bindings
-keep class is.xyz.mpv.** { *; }

# Keep NanoHTTPD server
-keep class fi.iki.elonen.** { *; }

# Keep Android classes
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View

# Keep QuickJS native bindings
-keep class app.cash.quickjs.** { *; }
-keepclassmembers class app.cash.quickjs.** {
    native <methods>;
}

# Keep Rhino JS Engine
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }

# Obfuscate everything else
-dontwarn **
-optimizationpasses 5
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# Hide original source file names
-renamesourcefileattribute MyBox
-keepattributes Signature,InnerClasses,EnclosingMethod

# Use confusing dictionary for names
-classobfuscationdictionary proguard-dictionary.txt
-obfuscationdictionary proguard-dictionary.txt
-packageobfuscationdictionary proguard-dictionary.txt

# Tencent TBS X5 Core Rules
-keep class com.tencent.smtt.** { *; }
-keep class com.tencent.tbs.** { *; }
-dontwarn com.tencent.smtt.**

