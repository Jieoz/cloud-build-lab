plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.14-log102")
val verCode by extra(128)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
