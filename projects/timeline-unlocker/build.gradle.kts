plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.5-log93")
val verCode by extra(119)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
