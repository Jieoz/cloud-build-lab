plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("2.6-log12")
val verCode by extra(38)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
