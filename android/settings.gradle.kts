// The Knura Android app: sets tags up with the phone's NFC by relaying knurd's commands (see
// knurd's NfcRelay). A separate Gradle build from the Java modules, so building those needs no
// Android SDK. Build with: ./gradlew -p android assembleDebug
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
    }
}

rootProject.name = "knura-android"
include(":app")
