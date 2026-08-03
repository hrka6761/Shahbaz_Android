/** Configures the Android-free geodesy and coordinate-formatting module. */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(projects.core.model)
    testImplementation(libs.junit)
}
