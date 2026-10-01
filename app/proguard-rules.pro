# AMPS release rules.
# kotlinx.serialization models are kept by their @Serializable companions.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class dev.amps.app.**$$serializer { *; }
-keepclassmembers class dev.amps.app.** {
    *** Companion;
}
-keepclasseswithmembers class dev.amps.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp platform fallbacks are optional at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
