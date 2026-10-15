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
    maven("https://repo.skriptlang.org/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    // The PacketEvents plugin has to be installed (plugin.yml depends on it), so it is not packaged.
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("com.github.SkriptLang:Skript:2.17.0")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.19")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
}

kotlin {
    jvmToolchain(25)
}

tasks {
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
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
