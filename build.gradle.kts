// AGP 9 ships built-in Kotlin for Android modules, so there is no
// org.jetbrains.kotlin.android plugin here; :core (pure JVM) uses KGP directly.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
