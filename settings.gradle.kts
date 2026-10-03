pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "MultiDesk Explorer"
include(":app")

include(":termux-shared")
project(":termux-shared").projectDir = File("termux-app/termux-shared")

include(":terminal-emulator")
project(":terminal-emulator").projectDir = File("termux-app/terminal-emulator")

include(":terminal-view")
project(":terminal-view").projectDir = File("termux-app/terminal-view")
