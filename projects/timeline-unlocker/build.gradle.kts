plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.4-log92")
val verCode by extra(118)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
