plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // Desde Kotlin 2.0 el compilador de Compose se aplica como plugin y su
    // versión va atada a la de Kotlin. `composeOptions` ya no sirve.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
