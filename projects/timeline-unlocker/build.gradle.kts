plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.0-log64")
val verCode by extra(90)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
