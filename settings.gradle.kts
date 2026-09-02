pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "swordfish"
include(":physics")
include(":app")

// Desktop layout preview. Compiles the real GaugeRenderer against Java2D
// stand-ins for android.graphics so panel layout can be seen without a car.
// Not shipped: nothing in :app or :physics depends on it.
include(":layout-harness")
project(":layout-harness").projectDir = file("tools/layout-harness")
