import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// El client ID de OAuth no se versiona. Se lee de local.properties o del entorno.
//   GOOGLE_OAUTH_CLIENT_ID=1234567890-abcdef.apps.googleusercontent.com
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
/** Un valor de local.properties o, si no está, del entorno. Null si no hay. */
fun secret(name: String): String? =
    localProps.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

val oauthClientId: String = secret("GOOGLE_OAUTH_CLIENT_ID") ?: ""

// La release se firma con otra clave, y Google identifica al cliente Android
// por paquete + huella de la firma: hace falta un client ID propio para ella.
//   GOOGLE_OAUTH_CLIENT_ID_RELEASE=...   (si no está, se usa el de arriba)
val oauthClientIdRelease: String = secret("GOOGLE_OAUTH_CLIENT_ID_RELEASE") ?: oauthClientId

// Google exige, para clientes Android/instalados, un redirect con el client ID
// invertido:
//   1234-abc.apps.googleusercontent.com  ->  com.googleusercontent.apps.1234-abc
fun redirectSchemeFor(clientId: String): String =
    if (clientId.endsWith(".apps.googleusercontent.com")) {
        "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
    } else {
        // Placeholder para que el manifest compile sin credenciales configuradas.
        "com.weto.booxcal.unconfigured"
    }

val oauthRedirectScheme: String = redirectSchemeFor(oauthClientId)

// Firma de release. El keystore no se versiona (ver .gitignore). En
// local.properties o en el entorno:
//   RELEASE_STORE_FILE=/ruta/booxcal-release.jks
//   RELEASE_STORE_PASSWORD=...
//   RELEASE_KEY_ALIAS=booxcal
//   RELEASE_KEY_PASSWORD=...
// Sin ellos, assembleRelease produce un APK sin firmar (no se puede instalar).
val releaseStoreFile: String? = secret("RELEASE_STORE_FILE")

android {
    namespace = "com.weto.booxcal"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.weto.booxcal"
        // BooxOS V4.2 en el Note Air 5C es Android 13 (SDK 33). 26 deja margen
        // para dispositivos Onyx anteriores y da java.time nativo sin desugaring.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Sube versionCode en cada release publicada (ver docs/RELEASE.md).
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
        buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"$oauthRedirectScheme\"")
        manifestPlaceholders["appAuthRedirectScheme"] = oauthRedirectScheme
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // El client ID de la release, con su esquema de redirección.
            buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientIdRelease\"")
            buildConfigField("String", "OAUTH_REDIRECT_SCHEME", "\"${redirectSchemeFor(oauthClientIdRelease)}\"")
            manifestPlaceholders["appAuthRedirectScheme"] = redirectSchemeFor(oauthClientIdRelease)
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("Sin RELEASE_STORE_FILE: el APK de release saldrá sin firmar. Ver docs/RELEASE.md.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
        jniLibs {
            // onyxsdk-pen, onyxsdk-pennative y mmkv empaquetan cada uno su
            // copia de libc++_shared.so. Son la misma biblioteca del NDK: vale
            // con quedarse con la primera.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

kotlin {
    compilerOptions {
        // 17 aunque se compile con un JDK 21: Android no admite todavía el
        // bytecode de 21, y el objetivo es independiente del JDK que corra Gradle.
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.kotlinx.serialization.json)

    // OAuth 2.0 + PKCE, sin depender de Google Play Services: los Boox no
    // siempre lo traen, y cuando lo traen no es de fiar.
    implementation(libs.appauth)
    implementation(libs.androidx.browser)

    // OCR de tinta, en el dispositivo.
    implementation(libs.mlkit.digital.ink)
    // Texto en imagen (impreso y manuscrito): las páginas de los PDF ajenos
    // se indexan a partir de su imagen.
    implementation(libs.mlkit.text.recognition)

    // Las notas viajan a Drive como PDF vectorial editable, y vuelven de él.
    implementation(libs.pdfbox.android)

    // SDK de Onyx: trazo con latencia baja y control de refresco e-ink.
    //
    // Arrastra medio ecosistema de 2016 —appcompat-v7 24.2.1 vía easypermissions,
    // RxJava 2, joda-time…— y la Support Library choca con AndroidX: ambas
    // declaran android.support.v4.os.ResultReceiver y compañía, y el empaquetado
    // se niega a continuar con clases duplicadas.
    //
    // Se excluye el grupo entero en vez de activar Jetifier: las únicas clases
    // de android.support que el SDK necesita de verdad (los stubs AIDL) las
    // sigue proporcionando androidx.core, y easypermissions no se usa desde
    // aquí, así que nunca llega a cargarse.
    // Sin esto, en Android 9+ el SDK de Onyx no puede tocar las APIs ocultas
    // que usa para el trazo rápido: se monta, no protesta y no entrega puntos.
    implementation(libs.hidden.api.bypass)
    implementation(libs.onyx.pen) {
        exclude(group = "com.android.support")
        exclude(group = "pub.devrel", module = "easypermissions")
    }
    implementation(libs.onyx.device) {
        exclude(group = "com.android.support")
        exclude(group = "pub.devrel", module = "easypermissions")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
