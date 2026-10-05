// Verification daemon. Placeholder until the HTTP API work starts.
plugins {
    application
}

dependencies {
    implementation(project(":knura-core"))
}

application {
    mainClass = "io.knura.knurd.Main"
}
