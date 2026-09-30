plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.6-log70")
val verCode by extra(96)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
