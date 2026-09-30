plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("2.7-log61")
val verCode by extra(87)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
