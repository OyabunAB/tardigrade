plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.java.library)
}

repositories {
    mavenCentral()
    mavenLocal()
}

group = properties["group"] as String
version = libs.versions.tardigrade

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
}

dependencies {
    api(project(":core"))
    api(libs.bundles.test)
    api(libs.bundles.logging)
}
