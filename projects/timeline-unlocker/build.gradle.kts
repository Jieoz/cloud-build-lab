plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.8-log96")
val verCode by extra(122)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
