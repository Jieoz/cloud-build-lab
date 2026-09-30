plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.10-log74")
val verCode by extra(100)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
