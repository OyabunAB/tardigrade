plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.java.library)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
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
    api(libs.ktor.server.core)
    testImplementation(libs.bundles.test)
    testImplementation(libs.ktor.server.test.host)
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "tardigrade-${project.name}"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/OyabunAB/tardigrade")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
