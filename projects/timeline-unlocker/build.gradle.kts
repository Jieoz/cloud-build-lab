plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("2.6-log40")
val verCode by extra(66)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
