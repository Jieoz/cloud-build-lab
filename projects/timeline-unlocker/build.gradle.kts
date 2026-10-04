plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.23-log111")
val verCode by extra(137)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
