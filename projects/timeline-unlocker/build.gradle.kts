plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.12-log100")
val verCode by extra(126)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
