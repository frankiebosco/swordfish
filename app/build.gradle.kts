import java.util.Properties

plugins {
    // AGP 9 has BUILT-IN Kotlin support. Applying org.jetbrains.kotlin.android
    // as well fails with "Cannot add extension with name 'kotlin'" -- the
    // separate plugin is now redundant, not merely optional.
    id("com.android.application")
}

android {
    namespace = "dev.swordfish"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.swordfish"
        minSdk = 28
        targetSdk = 37
        // Bump this on every build you want the head unit to pick up.
        //
        // Android Auto caches its binding to the CarAppService and will keep
        // serving OLD code after a reinstall -- force-stop does not help,
        // because the host rebinds instantly. A changed versionCode is the
        // cheap way to make it reload without dropping the DHU connection.
        // See tools/reload.bat.
        versionCode = 81
        versionName = "0.17.11"
    }

    // Release signing for Play Internal App Sharing.
    //
    // Play requires a signed release build; an unsigned or debug-signed APK
    // is rejected at upload. Credentials live in keystore.properties, which
    // is GITIGNORED along with the .jks itself.
    //
    // THE KEYSTORE IS PERMANENT. It is the app's identity on Play -- lose it
    // and dev.swordfish can never be updated under that package name again.
    // Back up both files outside this repo.
    //
    // The config is created only when the properties file is present, so a
    // fresh clone still builds debug without it.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) {
            keystorePropsFile.inputStream().use { load(it) }
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        // The debug build is a SEPARATE APP: dev.swordfish.debug.
        //
        // ## Why the suffix exists
        //
        // Once the release build came from Play, Play re-signed it with its
        // own key, so `adb install` of a local build fails with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE. The only way to sideload under
        // the same package name is to uninstall first -- which destroys
        // `installerPackageName=com.android.vending`, the Play attribution
        // that the launcher tile depends on and that cost real money and
        // several days to obtain.
        //
        // A suffixed package installs ALONGSIDE the Play build, so the fast
        // DHU loop costs nothing and the car build is never touched.
        //
        // ## Why this is safe on the DHU and useless in the car
        //
        // The launcher gate is Play ownership of the package name, and
        // `dev.swordfish.debug` has none -- so in the CAR it would be denied
        // exactly as the sideloaded build always was. But the DHU performs no
        // Play ownership check at all (measured: zero Finsky ownership
        // queries in a DHU session against two in a car session, same phone,
        // same APK, four minutes apart). So the debug build is for the DHU
        // and the emulator only.
        //
        // **The split is deliberate: iterate visually on .debug, ship through
        // Play for anything that has to run in the car.** A .debug build
        // looking right on the DHU is NOT evidence it works on real hardware
        // -- the DHU has already been proven to lie about the tile,
        // validation, and host-crash behaviour.
        debug {
            applicationIdSuffix = ".debug"
            // The names are overridden in src/debug/res/values/strings.xml
            // rather than with resValue(), which AGP 9 disables by default
            // ("Build Type debug contains custom resource values, but the
            // feature is disabled"). A debug source set is the idiomatic
            // route anyway and needs no global gradle.properties flag.
        }
    }

    testOptions {
        unitTests {
            // Car App Library builders touch android.os.Binder (via
            // OnClickListener marshalling), which throws "not mocked" in
            // local unit tests by default. Returning defaults lets the
            // template-construction tests run on the JVM instead of needing
            // an instrumented test or a head unit.
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The pure physics model. This dependency direction is one-way:
    // :app depends on :physics, never the reverse. That is what keeps the
    // model testable on a plain JVM with no Android SDK.
    implementation(project(":physics"))

    // Car App Library. BOTH artifacts are required for Android Auto:
    // `app` is the core, `app-projected` is the phone-projection half.
    // (`app-automotive` would be for Android Automotive OS — cars with
    // Android built into the dash. The ND2 runs Mazda Connect and projects
    // from the phone, so it is not us.)
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Android unit tests run on JUnit4 via AGP's default test runner.
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
    testImplementation("androidx.car.app:app-testing:1.7.0")
}
