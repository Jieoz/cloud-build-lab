plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.1-log65")
val verCode by extra(91)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
