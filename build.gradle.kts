plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.23-log87")
val verCode by extra(113)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
