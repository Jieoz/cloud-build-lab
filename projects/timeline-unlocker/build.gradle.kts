plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.6-log94")
val verCode by extra(120)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
