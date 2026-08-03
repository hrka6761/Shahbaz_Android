/** Configures the Android device-heading adapter module. */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ir.hrka.shahbaz.core.location"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
