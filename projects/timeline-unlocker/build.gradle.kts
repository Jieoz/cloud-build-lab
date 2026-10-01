plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.13-log101")
val verCode by extra(127)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
