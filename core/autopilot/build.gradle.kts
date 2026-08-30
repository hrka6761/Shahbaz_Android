/** Configures the future Shahbaz autopilot Android library module. */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ir.hrka.shahbaz.autopilot"
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
    implementation(projects.core.flightController)
    testImplementation(libs.junit)
}
