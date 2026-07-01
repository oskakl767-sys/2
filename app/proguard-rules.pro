# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.mdm.agent.** { *; }
-keepclassmembers class com.mdm.agent.** { *; }
-dontwarn io.socket.**
-keep class io.socket.** { *; }