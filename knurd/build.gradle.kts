// Verification daemon: HTTP tap endpoint on the JDK's built-in server, no third-party dependencies.
plugins {
    application
}

dependencies {
    implementation(project(":knura-core"))
}

application {
    mainClass = "io.knura.knurd.Main"
}

// Run from the repository root so `./gradlew :knurd:run` finds ./knurd.properties there.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
