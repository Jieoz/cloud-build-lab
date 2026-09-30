plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.17-log81")
val verCode by extra(107)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
