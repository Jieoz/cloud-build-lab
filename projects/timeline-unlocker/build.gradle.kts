plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.11-log99")
val verCode by extra(125)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
