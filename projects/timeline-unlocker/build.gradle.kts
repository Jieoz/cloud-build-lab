plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.2-log90")
val verCode by extra(116)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
