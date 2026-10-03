plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.16-log104")
val verCode by extra(130)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
