/** Configures the reusable, UI-free Android compass library module. */
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "ir.hrka"
version = "0.1.0"

android {
    namespace = "ir.hrka.compass"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 21
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
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ir.hrka"
            artifactId = "compass"
            version = project.version.toString()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name = "HRKA Compass"
                description = "A reusable, UI-free Android compass and device-orientation library."
            }
        }
    }
}
