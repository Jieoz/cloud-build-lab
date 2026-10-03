plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("4.18-log106")
val verCode by extra(132)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
