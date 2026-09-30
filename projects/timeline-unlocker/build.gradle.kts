plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.13-log77")
val verCode by extra(103)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
