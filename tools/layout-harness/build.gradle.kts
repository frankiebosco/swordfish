plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

/**
 * THE WHOLE POINT: the REAL renderer source is compiled here.
 *
 * Not a copy, not a reimplementation -- the same .kt files the app ships,
 * pulled in as an extra source directory and compiled against the Java2D
 * shims in src/main/kotlin/android/. If someone edits GaugeRenderer.kt, the
 * next harness run reflects it with no sync step, which is the only way a
 * preview tool stays honest.
 *
 * Only the files that are actually needed are listed. Pulling all of
 * `app/src/main/kotlin` would drag in Bluetooth, the poller and the Car App
 * Service, and the shim surface would balloon to match.
 */
sourceSets {
    main {
        // srcDir takes DIRECTORIES, so the app's car package is added whole
        // and then filtered down to the two files that actually render.
        // Including the rest would drag in the CarAppService, the Bluetooth
        // poller and the recorder, and the shim would have to grow to match.
        kotlin.srcDir("../../app/src/main/kotlin/dev/swordfish/car")
        kotlin.include(
            "**/GaugeRenderer.kt", "**/PanelState.kt",
            "**/GaugeFormat.kt", "**/SegmentDisplay.kt",
            "**/harness/**", "**/android/**", "**/androidx/**"
        )
    }
}

dependencies {
    implementation(project(":physics"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.swordfish.harness.SnapshotKt")
}

// Run from the REPO ROOT, not the module dir, so the default output path
// and any paths printed are relative to where the developer actually is.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}

// The numeric companion to the snapshots: prints the computed vertical
// stack so a fix can be written against pixel values rather than eyeballed.
// The interactive tuner: drag elements, get Kotlin back.
tasks.register<JavaExec>("tune") {
    group = "application"
    description = "Open the drag-to-tune layout window."
    mainClass.set("dev.swordfish.harness.TunerKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Print the panel's computed layout geometry."
    mainClass.set("dev.swordfish.harness.ProbeKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}
