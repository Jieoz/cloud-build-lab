plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.2-log66")
val verCode by extra(92)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
