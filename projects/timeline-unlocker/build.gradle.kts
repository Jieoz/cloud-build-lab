plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.7-log71")
val verCode by extra(97)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
