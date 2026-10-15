-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.gantoine.muxer.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
