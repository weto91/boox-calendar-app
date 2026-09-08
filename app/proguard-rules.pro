# SDK de Onyx: se llama por reflexión desde EinkRefresh y no debe ofuscarse.
-keep class com.onyx.android.sdk.** { *; }
-dontwarn com.onyx.android.sdk.**

# AppAuth
-keep class net.openid.appauth.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.weto.booxcal.** {
    *** Companion;
}
-keepclasseswithmembers class com.weto.booxcal.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class androidx.room.RoomDatabase { *; }

# ML Kit
-dontwarn com.google.mlkit.**

# pdfbox-android: carga filtros, fuentes y codificaciones por nombre.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# OkHttp / Okio / Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Las interfaces de la API de Google llevan las anotaciones de Retrofit.
-keep interface com.weto.booxcal.data.remote.google.** { *; }

# kotlinx.serialization: los serializadores generados de las clases @Serializable.
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# HiddenApiBypass: reflexión sobre clases del sistema.
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# Los widgets y el servicio de sus listas se instancian por nombre desde el manifiesto.
-keep class com.weto.booxcal.widget.** { *; }

# Nombres de fichero y línea en los volcados de error: hace legible un fallo en release.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# Dependencias opcionales que el SDK de Onyx y pdfbox referencian pero no
# empaquetan (joda-convert, la implementación de slf4j). R8 se niega a
# continuar si no se le dice que son de esperar.
-dontwarn org.joda.convert.**
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.**
