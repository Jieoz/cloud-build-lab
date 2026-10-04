plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.21-log109")
val verCode by extra(135)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
