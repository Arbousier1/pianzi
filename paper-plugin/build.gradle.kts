plugins {
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
    id("com.gradleup.shadow") version "9.3.1"
}

base {
    archivesName = "liar-bar-paper-plugin"
}

dependencies {
    paperweight.paperDevBundle(rootProject.property("paperVersion") as String)

    implementation(project(":paper-adapter"))

    compileOnly("com.github.MilkBowl:VaultAPI:${rootProject.property("vaultapiVersion")}")
    compileOnly("com.github.retrooper:packetevents-spigot:${rootProject.property("packeteventsVersion")}")

    implementation("com.h2database:h2:${rootProject.property("h2Version")}")
    implementation("org.mariadb.jdbc:mariadb-java-client:${rootProject.property("mariadbVersion")}")
    implementation("com.zaxxer:HikariCP:${rootProject.property("hikariVersion")}")
    implementation("com.github.ben-manes.caffeine:caffeine:${rootProject.property("caffeineVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind:${rootProject.property("jacksonDatabindVersion")}")
}

// Use Mojang mappings — no reobf needed for Paper 1.20.5+
paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    jar {
        archiveBaseName.set("liar-bar-paper-plugin")
    }

    shadowJar {
        archiveBaseName.set("liar-bar-paper-plugin")

        // Shade internal modules + runtime libs into the final JAR
        dependencies {
            include(project(":core"))
            include(project(":paper-adapter"))
            include(dependency("com.h2database:h2"))
            include(dependency("org.mariadb.jdbc:mariadb-java-client"))
            include(dependency("com.zaxxer:HikariCP"))
            include(dependency("com.github.ben-manes.caffeine:caffeine"))
            include(dependency("com.fasterxml.jackson.core:jackson-databind"))
            include(dependency("com.fasterxml.jackson.core:jackson-core"))
            include(dependency("com.fasterxml.jackson.core:jackson-annotations"))
        }

        // Note: relocate disabled — Shadow's bundled ASM does not yet support Java 25 (class version 69)
        // relocate("com.zaxxer.hikari", "cn.pianzi.liarbar.libs.hikari")

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
