plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "com.oshi.desktop"
version = "0.0.1"

/**
 * Where the two shipped trees live.
 *
 * Defaulted RELATIVE to this project rather than read from gradle.properties, because an
 * absolute `/Users/...` path in a checked-in properties file is a build that only works
 * on one machine — and Windows and Linux are the whole point of this pass. Override with
 * `-PoshiAndroidRoot=...` when the layout differs.
 */
val oshiAndroidRoot: String = providers.gradleProperty("oshiAndroidRoot").orNull
    ?: rootDir.parentFile.resolve("OSHI-Android").absolutePath

/** The iOS tree. Read only by tests, and only to diff wire contracts against Swift. */
val oshiIosRoot: String = providers.gradleProperty("oshiIosRoot").orNull
    ?: rootDir.parentFile.resolve("OSHI").absolutePath

/**
 * THE LOAD-BEARING IDEA OF THIS SKELETON.
 *
 * The desktop client does not re-implement OSHI's crypto. It compiles the SAME
 * Kotlin source files the Android app compiles, straight out of the Android tree,
 * with no copy step. The Android tree is read, never written.
 *
 * That is possible because these six files were deliberately written as pure JVM
 * (BouncyCastle X25519 + javax.crypto AES-GCM/HMAC, no android.* imports) so that
 * Android could vector-test them on the JVM against iOS's known-answer vectors.
 * That decision, made for testability, is what makes a third platform cheap.
 *
 * Every other file in network/v2 is excluded because it drags in android.util.Base64,
 * android.util.Log, BuildConfig, Hilt or SharedPreferences. Those are the transport
 * and storage seams the desktop client owns; see src/main/kotlin/.../DesktopV2*.kt.
 *
 * TRIPWIRE: if anyone adds an `import android.*` to one of the six files below, THIS
 * BUILD BREAKS. That is the point. A red desktop build is the cheapest possible alarm
 * that the shared crypto core has stopped being shared.
 */
val sharedV2Sources = "$oshiAndroidRoot/app/src/main/java/com/oshi/messenger/network/v2"

sourceSets {
    main {
        kotlin.srcDir(sharedV2Sources)
        kotlin.include(
            "**/OSHICryptoV2.kt",
            "**/OSHICryptoV2Streaming.kt",
            "**/OSHIRatchetV2.kt",
            "**/V2Session.kt",
            "**/V2FileKeyMessage.kt",
            "**/V2RetryBudget.kt",
            // ...plus everything this project actually owns:
            "com/oshi/desktop/**",
        )
    }
}

dependencies {
    // Pinned to EXACTLY what OSHI-Android uses (app/build.gradle.kts:218, :296).
    // A different BouncyCastle is a parity risk, not a housekeeping detail.
    implementation("org.bouncycastle:bcprov-jdk18on:1.76")
    implementation("org.json:json:20231013")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application {
    mainClass.set("com.oshi.desktop.MainKt")
}

tasks.test {
    // Lets the tripwire test be pointed at a mutated copy of the Android tree, so the
    // guard can be watched FAILING instead of merely being green.
    systemProperty("oshi.android.root", providers.gradleProperty("oshiAndroidRootOverride").orNull ?: oshiAndroidRoot)
    // The mesh wire contract is defined by TWO shipped implementations, so the parity
    // tests read both trees. Absent either one, those tests skip rather than fail — a
    // Linux build box has no reason to hold the iOS sources.
    systemProperty("oshi.ios.root", providers.gradleProperty("oshiIosRootOverride").orNull ?: oshiIosRoot)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
