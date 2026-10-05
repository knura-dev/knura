// Provisioning and management CLI. Placeholder until the reader work starts.
plugins {
    application
}

dependencies {
    implementation(project(":knura-core"))
}

application {
    mainClass = "io.knura.knurctl.Main"
}
