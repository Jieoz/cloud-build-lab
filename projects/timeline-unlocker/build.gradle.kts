plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("3.19-log83")
val verCode by extra(109)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
