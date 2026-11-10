plugins {
    java
    id("com.gradleup.shadow") version "8.3.9"
}

version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        content { includeGroup("org.spigotmc") }
    }
    maven("https://repo.roinujnosde.me/releases/") {
        content { includeGroup("net.sacredlabyrinth.phaed.simpleclans") }
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("net.sacredlabyrinth.phaed.simpleclans:SimpleClans:2.19.2") { isTransitive = false }
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.test { useJUnitPlatform() }
tasks.jar { enabled = false }
tasks.shadowJar {
    archiveClassifier = ""
    relocate("com.google.gson", "scstats.collector.lib.gson")
}
tasks.assemble { dependsOn(tasks.shadowJar) }
