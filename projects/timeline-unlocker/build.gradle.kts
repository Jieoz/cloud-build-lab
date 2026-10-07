plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.26-log114")
val verCode by extra(140)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
