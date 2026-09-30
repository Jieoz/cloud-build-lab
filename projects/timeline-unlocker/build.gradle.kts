plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.21-log85")
val verCode by extra(111)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
