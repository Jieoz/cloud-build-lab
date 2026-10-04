plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.22-log110")
val verCode by extra(136)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
