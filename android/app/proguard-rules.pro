# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.echopanel.app.**$$serializer { *; }
-keepclassmembers class com.echopanel.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.echopanel.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Agora RTC SDK
-keep class io.agora.** { *; }
-dontwarn io.agora.**

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
