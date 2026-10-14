-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.gantoine.cmux.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
