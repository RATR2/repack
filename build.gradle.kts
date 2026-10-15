plugins {
    kotlin("jvm") version "2.5.0-Beta1"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
    implementation("com.github.retrooper:packetevents-spigot:2.14.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.14.0")
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
        // PacketEvents is shaded, so it is relocated to avoid clashing with a copy another plugin ships.
        relocate("com.github.retrooper.packetevents", "io.github.ratr2.repack.libs.packetevents")
        relocate("io.github.retrooper.packetevents", "io.github.ratr2.repack.libs.packetevents.spigot")
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
