plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("2.6-log16")
val verCode by extra(42)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
