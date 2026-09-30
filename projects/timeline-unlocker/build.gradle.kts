plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.3-log67")
val verCode by extra(93)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
