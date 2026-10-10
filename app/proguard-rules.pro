-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class dev.gl.license.**$$serializer { *; }
-keepclassmembers class dev.gl.license.** {
    *** Companion;
}
-dontwarn org.bouncycastle.**
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}
