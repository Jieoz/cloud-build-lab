plugins {
    alias(libs.plugins.agp.app) apply false
}

val verName by extra("2.8-log62")
val verCode by extra(88)

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
