/** Configures the Shahbaz autonomous mission-policy Android library. */
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "ir.hrka"
version = "0.1.0"

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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(projects.core.flightContracts)
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    implementation(projects.core.domain)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ir.hrka"
            artifactId = "autopilot"
            version = project.version.toString()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name = "Shahbaz Autopilot"
                description = "Deterministic point-to-point autonomous mission policy for Shahbaz."
            }
        }
    }
}
