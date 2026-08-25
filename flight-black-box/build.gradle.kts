/** Configures the independent Flight Black Box diagnostics Android library. */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.shahbaz.flightblackbox"
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
