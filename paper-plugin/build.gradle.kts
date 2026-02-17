plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

dependencies {
    paperweight.paperDevBundle(rootProject.property("paperVersion") as String)

    implementation(project(":paper-adapter"))

    compileOnly("com.github.MilkBowl:VaultAPI:${rootProject.property("vaultapiVersion")}")
    compileOnly("com.github.retrooper:packetevents-spigot:${rootProject.property("packeteventsVersion")}")

    implementation("com.h2database:h2:${rootProject.property("h2Version")}")
    implementation("org.mariadb.jdbc:mariadb-java-client:${rootProject.property("mariadbVersion")}")
    implementation("com.zaxxer:HikariCP:${rootProject.property("hikariVersion")}")
}

// Use Mojang mappings — no reobf needed for Paper 1.20.5+
paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    shadowJar {
        // Shade internal modules into the final JAR
        dependencies {
            include(project(":core"))
            include(project(":paper-adapter"))
        }

        // Exclude signature files
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

        archiveClassifier.set("")
    }

    assemble {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf(
            "version" to project.version
        )
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
