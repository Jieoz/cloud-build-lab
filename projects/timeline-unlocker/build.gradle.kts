plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.24-log112")
val verCode by extra(138)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
