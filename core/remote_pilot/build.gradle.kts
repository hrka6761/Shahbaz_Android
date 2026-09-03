/** Configures the future Shahbaz remote-pilot Android library module. */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ir.hrka.shahbaz.remotepilot"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
