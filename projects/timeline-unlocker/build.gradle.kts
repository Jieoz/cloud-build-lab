plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.5-log69")
val verCode by extra(95)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
