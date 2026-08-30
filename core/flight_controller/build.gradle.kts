/** Configures the independent Shahbaz Android flight-controller library. */
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "ir.hrka"
version = "0.1.0"

android {
    namespace = "ir.hrka.shahbaz.flightcontroller"
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
    implementation(projects.core.flightBlackBox)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ir.hrka"
            artifactId = "flight-controller"
            version = project.version.toString()
            afterEvaluate {
                from(components["release"])
            }
            pom {
                name = "Shahbaz Flight Controller"
                description = "An independent Kotlin flight-controller engine for Android-based quadcopters."
            }
        }
    }
}
