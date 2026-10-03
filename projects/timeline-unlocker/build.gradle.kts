plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.17-log105")
val verCode by extra(131)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
