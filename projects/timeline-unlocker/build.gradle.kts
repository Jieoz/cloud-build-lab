plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.9-log73")
val verCode by extra(99)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
