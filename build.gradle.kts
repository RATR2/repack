plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.viaversion.com")
    maven("https://repo.skriptlang.org/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // The PacketEvents plugin has to be installed (plugin.yml depends on it), so it is not packaged.
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("com.github.SkriptLang:Skript:2.17.0")
    compileOnly("com.viaversion:viaversion-api:5.12.1")
    // Only their own classes are needed: their dependency versions would clash with the Paper API's.
    for (notation in listOf(
        "com.sk89q.worldguard:worldguard-bukkit:7.0.15",
        "com.sk89q.worldguard:worldguard-core:7.0.15",
        "com.sk89q.worldedit:worldedit-bukkit:7.4.2",
        "com.sk89q.worldedit:worldedit-core:7.4.2",
    )) {
        compileOnly(notation) { isTransitive = false }
    }
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

kotlin {
    // Tests live in the git-ignored .dev folder and only run on the developer's machine.
    sourceSets.named("test") { kotlin.srcDir(".dev/test/kotlin") }
    // Built with JDK 25, but the classes are Java 21 ones so the jar also loads on the Java 21 that 1.21.x servers run.
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

tasks {
    withType<JavaCompile> {
        options.release.set(21)
    }

    build {
        dependsOn(shadowJar)
    }

    // The shaded jar (with the Kotlin runtime) is the one to deploy, so give it the plain name.
    shadowJar {
        archiveClassifier.set("")
    }

    test {
        useJUnitPlatform()
    }

    jar {
        archiveClassifier.set("plain")
    }

    runServer {
        minecraftVersion("26.3")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.property("version", version.toString())
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
