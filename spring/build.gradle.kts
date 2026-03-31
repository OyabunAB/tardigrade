plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.java.library)
    alias(libs.plugins.maven.publish)
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/release") }
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
    implementation(platform(libs.spring.boot.bom))
    compileOnly("org.springframework:spring-context")
    compileOnly("org.springframework:spring-aop")
    compileOnly("org.springframework:spring-web")
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.aspectjweaver)
    testImplementation(libs.bundles.test)
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-aop")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-test")
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.aspectjweaver)
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
