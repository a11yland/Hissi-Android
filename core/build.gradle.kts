// Pure JVM module: the hand-ported counterpart of the iOS `Shared/` logic.
// No Android dependencies allowed — tests run on plain JVM, on CI and locally,
// against the same fixtures as the iOS Swift package (Hissi-iOS/HissiTests).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Keep javac aligned with the Kotlin jvmTarget regardless of the JDK running
// Gradle (KGP fails the build on mismatching JVM targets).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // api: the @Serializable core models are part of this module's surface —
    // the app serializes them into DataStore.
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
    // The Kotlin tests run against the same JSON fixtures as the iOS app's
    // Swift package (copied from Hissi-iOS/HissiTests/Tests/Fixtures) so
    // behavioral drift between the two ports surfaces in CI.
    val fixtures = projectDir.resolve("src/test/fixtures")
    systemProperty("hissi.fixtures.dir", fixtures.absolutePath)
    inputs.dir(fixtures)
}
