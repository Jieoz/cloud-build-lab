plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.30-keeperfix118")
val verCode by extra(144)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
