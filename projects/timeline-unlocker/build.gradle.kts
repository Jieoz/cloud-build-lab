plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.20-log108")
val verCode by extra(134)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
