pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "liar-bar"

include("core")
include("paper-adapter")
include("paper-plugin")
