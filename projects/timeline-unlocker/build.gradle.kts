plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.31-align119")
val verCode by extra(145)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
