plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.22-log86")
val verCode by extra(112)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
