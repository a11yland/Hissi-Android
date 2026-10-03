import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The transit.accessibility.cloud Bearer token, machine-local in
// local.properties (gitignored). CI only assembles — an empty token builds
// fine and simply fails requests at runtime.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val transitToken: String = localProperties.getProperty("hissi.transitToken") ?: ""
// Maps SDK key, restricted to package + signing SHA-1 (safe inside the APK,
// still kept out of the repo). Empty on CI — the snippet then renders blank,
// the open-in-maps action keeps working.
val mapsApiKey: String = localProperties.getProperty("hissi.mapsApiKey") ?: ""
// Play upload keystore, machine-local like the tokens above (the .jks and its
// passwords never enter the repo). Absent on CI, which only assembles debug —
// the release build type then simply stays unsigned.
val uploadStoreFile: String? = localProperties.getProperty("hissi.upload.storeFile")

android {
    namespace = "com.a11yland.hissi"
    // Current AndroidX stables (core-ktx 1.19+, Compose BOM 2026.08) require
    // compiling against 37; targetSdk deliberately stays at 36 (issue #8).
    compileSdk = 37

    defaultConfig {
        // Matches the iOS bundle id — deliberately not lowercase; the code
        // namespace above stays so.
        applicationId = "com.a11yland.Hissi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Escaped: a token containing " or \ must not break the generated
        // source with a cryptic compile error.
        val escapedToken = transitToken.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "TRANSIT_TOKEN", "\"$escapedToken\"")
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = rootProject.file(uploadStoreFile)
                storePassword = localProperties.getProperty("hissi.upload.storePassword")
                keyAlias = localProperties.getProperty("hissi.upload.keyAlias")
                keyPassword = localProperties.getProperty("hissi.upload.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildToolsVersion = "36.0.0"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.maps.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.work.runtime)
    implementation(libs.reorderable)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
