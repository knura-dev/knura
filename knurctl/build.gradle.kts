// Provisioning and management CLI. Talks to tags over PC/SC (javax.smartcardio, part of the JDK).
plugins {
    application
}

dependencies {
    implementation(project(":knura-core"))
}

application {
    mainClass = "io.knura.knurctl.Main"
}

// Run from the repository root so `./gradlew :knurctl:run` shares ./knurd.properties with knurd.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
