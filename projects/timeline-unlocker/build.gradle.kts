plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.0-log88")
val verCode by extra(114)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
