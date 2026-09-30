plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.18-log82")
val verCode by extra(108)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
