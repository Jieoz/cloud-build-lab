plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.25-log113")
val verCode by extra(139)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
