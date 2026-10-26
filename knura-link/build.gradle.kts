// Home link: receives taps verified by a hosted Knura account and runs their knurgN actions at
// home. It dials out over a WebSocket, so the home network needs no open ports. JDK only.
plugins {
    application
}

dependencies {
    implementation(project(":knura-core"))
    implementation(project(":knurgn"))
}

application {
    mainClass = "io.knura.link.Main"
    applicationName = "knura-link"
}

// Run from the repository root so `./gradlew :knura-link:run` finds ./link.properties there.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    standardInput = System.`in`
}
