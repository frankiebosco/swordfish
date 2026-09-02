plugins {
    // AGP 9 provides Kotlin support for Android modules itself, so
    // org.jetbrains.kotlin.android must NOT be applied alongside it.
    // The JVM plugin is still needed for :physics, which is plain Kotlin.
    id("com.android.application") version "9.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false
}
