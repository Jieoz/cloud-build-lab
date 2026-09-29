plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("2.6-log56")
val verCode by extra(82)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
