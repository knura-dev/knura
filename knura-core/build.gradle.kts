// Crypto and NTAG 424 DNA logic. No third-party runtime dependencies: JDK only.
plugins {
    `java-library`
    // FakeNtag424, a simulated tag, shared with the other modules' tests.
    `java-test-fixtures`
}

dependencies {
    // Test only: reads back the QR codes QrCode draws.
    testImplementation("com.google.zxing:core:3.5.4")
}
