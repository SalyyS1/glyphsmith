plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.salyvn.glyphsmith"
version = "0.3.0"

repositories {
    mavenCentral()
}

dependencies {
    // A spec file is hand-written YAML, so it is parsed with SafeConstructor: no tag resolution and no
    // object instantiation, because a spec is data and never code.
    implementation("org.yaml:snakeyaml:2.6")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.github.ajalt.clikt:clikt:5.1.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.salyvn.glyphsmith.MainKt"
    applicationName = "glyphsmith"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// One source of truth for the version: the build stamps it into a resource the CLI reads back, so the
// number in `--version` cannot drift from the number in build.gradle.kts.
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("glyphsmith-version.properties") {
        expand("version" to project.version.toString())
    }
}

// The thin jar gets a classifier so it cannot overwrite the shaded artifact that users download.
tasks.jar {
    archiveClassifier = "thin"
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveBaseName = "glyphsmith"
    manifest {
        attributes["Main-Class"] = "com.salyvn.glyphsmith.MainKt"
        attributes["Implementation-Version"] = project.version.toString()
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
