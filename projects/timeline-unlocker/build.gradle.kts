plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.16-log80")
val verCode by extra(106)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
