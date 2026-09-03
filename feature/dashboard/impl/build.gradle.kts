/** Configures the Shahbaz flight-dashboard feature implementation. */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

val experimentalPhysicalActuators = providers
    .gradleProperty("shahbaz.experimentalPhysicalActuators")
    .map { raw ->
        raw.toBooleanStrictOrNull()
            ?: error(
                "Gradle property shahbaz.experimentalPhysicalActuators must be exactly true or false",
            )
    }
    .orElse(false)

android {
    namespace = "ir.hrka.shahbaz.feature.dashboard.impl"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
        buildConfigField(
            "boolean",
            "EXPERIMENTAL_PHYSICAL_ACTUATORS",
            experimentalPhysicalActuators.get().toString(),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    api(projects.core.autopilot)
    api(projects.core.model)
    api(projects.core.compass)
    api(projects.core.hardwareConnection)

    implementation(projects.core.designsystem)
    implementation(projects.core.domain)
    implementation(projects.core.flightContracts)
    implementation(projects.core.flightController)
    implementation(projects.core.flightBlackBox)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.maplibre.compose) {
        exclude(group = "org.maplibre.gl", module = "android-sdk")
    }
    implementation(libs.maplibre.android.opengl)

    testImplementation(libs.junit)
}
