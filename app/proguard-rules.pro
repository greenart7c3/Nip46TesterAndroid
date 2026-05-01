# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
