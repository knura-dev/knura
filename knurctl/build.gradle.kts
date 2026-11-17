// Provisioning and management CLI. Talks to tags over PC/SC (javax.smartcardio, part of the JDK).
// ZXing draws the QR code that pairs a phone (knurctl admin pair-phone).
plugins {
    application
}

dependencies {
    implementation(project(":knura-core"))
    implementation("com.google.zxing:core:3.5.4")
}

application {
    mainClass = "io.knura.knurctl.Main"
}

// Run from the repository root so `./gradlew :knurctl:run` shares ./knurd.properties with knurd.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
