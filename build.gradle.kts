import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.badwolfmc"
version = providers.gradleProperty("pluginVersion").orElse("1.0.0-SNAPSHOT").get()

val pluginVersion = version.toString()
val sqliteNativePath = "org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.112-stable")
    compileOnly("net.luckperms:api:5.5")

    implementation("org.xerial:sqlite-jdbc:3.53.2.1") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveClassifier.set("")

    // RoleRewards is deployed only on Ubuntu Linux x86_64/glibc. Xerial's
    // default SQLite JDBC JAR contains native libraries for many platforms;
    // keep the universal dependency for cross-platform development/tests, but
    // ship only the native library the production server can actually load.
    eachFile {
        if (path.startsWith("org/sqlite/native/") && path != sqliteNativePath) {
            exclude()
        }
    }

    manifest {
        attributes["RoleRewards-Target-Platform"] = "linux-x86_64-glibc"
    }
}

val verifyTargetedJar by tasks.registering {
    group = "verification"
    description = "Verifies that the shaded plugin JAR contains only the targeted SQLite native library."
    dependsOn(tasks.shadowJar)

    doLast {
        val jarFile = tasks.shadowJar.get().archiveFile.get().asFile
        val nativeEntries = java.util.zip.ZipFile(jarFile).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("org/sqlite/native/") && !it.endsWith("/") }
                .toList()
        }

        if (nativeEntries != listOf(sqliteNativePath)) {
            throw GradleException(
                "Expected exactly $sqliteNativePath in ${jarFile.name}, but found: " +
                    nativeEntries.joinToString().ifEmpty { "<none>" }
            )
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar, verifyTargetedJar)
}
