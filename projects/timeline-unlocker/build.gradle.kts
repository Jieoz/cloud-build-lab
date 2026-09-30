plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("2.9-log63")
val verCode by extra(89)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
