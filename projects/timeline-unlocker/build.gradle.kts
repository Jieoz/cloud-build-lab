plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.8-log72")
val verCode by extra(98)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
