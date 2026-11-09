// Layer-based video codec, CSCI 576. Java 21, no runtime dependencies.
// FFmpeg is called as an external process (VideoIO), not linked.

plugins {
    java
}

group = "codec"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Single fat JUnit jar, copied into build-tools/ so tests can also run
// in an offline sandbox with plain javac. See README "Offline test run".
val vmTestTools = configurations.create("vmTestTools")

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    vmTestTools("org.junit.platform:junit-platform-console-standalone:1.11.4")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

// Zero warnings policy (00 rules section 8) for main code.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Fixed jar name so the launcher scripts never change.
tasks.jar {
    archiveFileName = "layer-codec.jar"
}

tasks.register<Copy>("vendorJunit") {
    description = "Copies the JUnit console jar to build-tools/ for offline test runs."
    from(vmTestTools)
    into(layout.projectDirectory.dir("build-tools"))
}
