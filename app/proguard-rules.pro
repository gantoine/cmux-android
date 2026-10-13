-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class dev.cmuxandroid.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
