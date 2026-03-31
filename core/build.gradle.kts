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
    api(libs.bundles.core)
    api(libs.bundles.resilience4j)
    implementation(libs.bundles.logging)
    testImplementation(project(":testing"))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
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
