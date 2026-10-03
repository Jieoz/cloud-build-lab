plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.19-log107")
val verCode by extra(133)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
