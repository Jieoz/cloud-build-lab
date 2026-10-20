plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.27-log115")
val verCode by extra(141)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
