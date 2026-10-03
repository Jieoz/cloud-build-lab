plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.15-log103")
val verCode by extra(129)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
