plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.7-log95")
val verCode by extra(121)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
