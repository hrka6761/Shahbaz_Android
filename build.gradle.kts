/** Declares version-catalog plugins shared by the modules in this build. */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/** Fails when flight-stack implementations acquire compile/runtime dependencies on one another. */
tasks.register("verifyFlightModuleIndependence") {
    group = "verification"
    val isolatedModulePaths = setOf(
        ":core:autopilot",
        ":core:flight_controller",
        ":core:hardware_connection",
        ":core:remote_pilot",
    )
    doLast {
        val violations = isolatedModulePaths.flatMap { ownerPath ->
            project(ownerPath).configurations.flatMap { configuration ->
                configuration.dependencies
                    .withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                    .filter { dependency ->
                        dependency.path != ownerPath && dependency.path in isolatedModulePaths
                    }
                    .map { dependency ->
                        "$ownerPath:${configuration.name} -> ${dependency.path}"
                    }
            }
        }.distinct().sorted()
        check(violations.isEmpty()) {
            "Flight implementation modules must communicate through neutral contracts/adapters:\n" +
                violations.joinToString("\n")
        }
    }
}
