-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.gantoine.muxer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
