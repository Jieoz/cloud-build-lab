plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.1-log89")
val verCode by extra(115)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
