// `java.util.*` cannot be written inline anywhere below: in a Gradle Kotlin DSL script
// `java` resolves to the JavaPluginExtension, not the package, so `java.util.X` fails to
// compile with "Unresolved reference: util". Import instead.
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * Give `run` the real console.
 *
 * Gradle's JavaExec hands the program an EMPTY stdin by default, so an interactive CLI
 * reads null on its first line and exits immediately — which looks exactly like a program
 * that started, printed its banner and crashed. Both `--client` and `--mesh` are REPLs;
 * without this they can only ever be run non-interactively.
 */
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
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

    // ------------------------------------------------------------------ the zero-test guard
    //
    // "Executed 0 tests" + "BUILD SUCCESSFUL" is a green that means nothing, and it is the
    // exact way a CI matrix on three operating systems can report health it never measured:
    // a bad `--tests` filter, a source set that did not compile into the test task, a
    // whole class assumed away. So the test task counts what actually RAN and fails if
    // that number is below `-PminTests` (default 1).
    //
    // Two things this guard does NOT cover, which is why CI does not rely on it alone:
    //   * an UP-TO-DATE or NO-SOURCE test task never reaches `doLast`, so the count is
    //     never taken. CI therefore passes `--rerun` AND re-checks the JUnit XML in a
    //     separate shell step after deleting build/test-results first.
    //   * SKIPPED is counted separately and printed by name. A test that assumed itself
    //     away is not evidence of anything, and on the platforms this project cannot run
    //     locally (Windows, Linux) the skips are the whole story — see
    //     OSHI_EXPECT_SECRET_STORE in SecretStoreTest, which turns "no OS key store on
    //     this machine" from a skip into a failure when CI says there must be one.
    val minTests = (providers.gradleProperty("minTests").orNull ?: "1").toInt()
    val ran = AtomicInteger()
    val skipped: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
        override fun beforeTest(test: TestDescriptor) {}
        override fun afterTest(test: TestDescriptor, result: TestResult) {
            if (result.resultType == TestResult.ResultType.SKIPPED) {
                skipped.add("${test.className}.${test.name}")
            } else {
                ran.incrementAndGet()
            }
        }
    })
    doLast {
        val executed = ran.get()
        println("[test-census] executed=$executed skipped=${skipped.size} (floor=$minTests)")
        if (skipped.isNotEmpty()) {
            println("[test-census] SKIPPED — these measured nothing on this machine:")
            skipped.sorted().forEach { println("[test-census]   - $it") }
        }
        if (executed < minTests) {
            throw GradleException(
                "only $executed test(s) actually ran (floor: $minTests). A build that is green because " +
                    "it ran nothing is worse than a red one: it reports health it never measured. " +
                    "Skipped: ${skipped.size}."
            )
        }
    }
}

// =====================================================================================
// PACKAGING — PARITY.md row 1.6
// =====================================================================================
//
// jpackage, out of the JDK. No new Gradle plugin and no new dependency (Working rule 4):
// `jpackage` has shipped in the JDK since 14 and this project already requires a JDK to
// build, so the .msi/.deb/.rpm pipeline costs zero dependencies. The Gradle plugins that
// wrap it (beryx/runtime, Compose's own) buy convenience and cost a plugin whose release
// cadence this project would then be pinned to; the whole surface here is ~12 command
// line flags per platform, which is cheaper to read than a plugin's DSL.
//
// WHAT THIS IS NOT. jpackage only builds a NATIVE INSTALLER on its own platform: there is
// no cross-building. A .msi comes from a Windows box, .deb/.rpm from a Linux box, .dmg/
// .pkg from a Mac. That is not a limitation of this file, it is jpackage's design, and it
// is why the packaging jobs in CI are a three-OS matrix rather than one Linux job.
//
// EXTERNAL TOOLCHAIN each platform's installer needs, none of which is a Gradle dependency:
//   * Windows .msi  — WiX Toolset **v3** (candle.exe/light.exe) on PATH. jpackage 17 does
//                     NOT understand WiX v4+. Absent it, jpackage fails with
//                     "Can not find WiX tools" — a clear error, not a silent skip.
//   * Linux .deb    — dpkg-deb + fakeroot.
//   * Linux .rpm    — rpm-build.
//   * macOS .dmg    — nothing extra (hdiutil is part of the OS).
//
// SIGNING IS A DOCUMENTED GAP, NOT A FEATURE. Nothing below signs anything, and no
// credential is invented anywhere in this repository. What each platform would actually
// require, so the gap can be costed rather than discovered at release time:
//
//   * Windows Authenticode — an OV or EV code-signing certificate from a CA. Since June
//     2023 the private key must live on FIPS 140-2 Level 2 hardware (a token, or a cloud
//     HSM such as Azure Trusted Signing / DigiCert KeyLocker), so it CANNOT be a .pfx in
//     a GitHub secret. Signing is `signtool sign /fd SHA256 /tr <RFC3161 timestamp URL>`
//     over both the launcher .exe inside the app image AND the finished .msi. Unsigned,
//     SmartScreen shows "Windows protected your PC" to every early downloader, and an OV
//     certificate has to build reputation before that stops.
//   * Linux — Debian repository signing is a detached OpenPGP signature over the apt
//     `Release` file (`gpg --clearsign`/`debsign`), and RPM signing is `rpm --addsign`
//     with a GPG key whose public half users must import. A bare .deb/.rpm downloaded
//     from a web page installs unsigned today with a warning; a real apt/dnf repo cannot
//     exist without that key and a place to host it.
//   * macOS — a Developer ID Application certificate plus notarisation
//     (`xcrun notarytool submit` + `xcrun stapler staple`). This one the org already has
//     for the shipped Mac Catalyst app, so it is the only platform where the credential
//     exists today.
//
// UPDATES are likewise NOT implemented: an .msi with a stable --win-upgrade-uuid upgrades
// in place when a newer version is installed over it, and apt/dnf upgrade from a repo
// that does not exist yet. There is no in-app updater and this file must not pretend
// otherwise. That is the honest state of row 1.6's "updates" third.

/** Installed application name. Also the .app / Start Menu / launcher name. */
val appName = "OSHI"

/** Debian/RPM package name — lowercase, no spaces, per both packaging policies. */
val linuxPackageName = "oshi-desktop"

/**
 * Installer version. Override with `-PappVersion=1.2.3`.
 *
 * THE PROJECT VERSION IS NOT A LEGAL INSTALLER VERSION, and this was OBSERVED, not read
 * in a manual. `version` is `0.0.1`, and jpackage 17 on macOS refuses it outright:
 *
 *     Bundler Mac Application Image skipped because of a configuration problem:
 *     The first number in an app-version cannot be zero or negative.
 *
 * (CFBundleVersion has to start at 1.) Windows adds its own ceilings — MSI parses
 * MAJOR.MINOR.BUILD with MAJOR and MINOR in 0..255 and BUILD in 0..65535 — and rpm
 * forbids '-' in a version.
 *
 * So the three platforms do not agree on what a version is, and the build must not paper
 * over that. It would be easy to silently rewrite `0.0.1` into `1.0.0` and always
 * succeed; that puts a version number on a shipped installer that the project does not
 * have, which is the kind of quiet fiction this repository's whole review history is
 * about. Instead [requireLegalAppVersion] fails, naming the exact rule that was broken.
 * A release passes `-PappVersion`; a smoke run passes `-PappVersion=1.0.0`.
 */
val appVersion: String = providers.gradleProperty("appVersion").orNull ?: version.toString()

/**
 * Fail early, and say which platform's rule the version broke.
 *
 * jpackage's own message is decent on macOS and absent on the paths that only blow up
 * later (an MSI whose MAJOR is 300 fails inside WiX, hundreds of lines in). Checking all
 * three rule sets here means a Windows-only version problem is visible from a Mac.
 */
fun requireLegalAppVersion(taskName: String, type: String) {
    fun bad(why: String): Nothing = throw GradleException(
        "$taskName: '$appVersion' is not a legal jpackage --app-version for a $type on this host. $why\n" +
            "  Pass -PappVersion=1.2.3. The project's own version is '$version', which jpackage will not " +
            "accept everywhere; this build will not quietly relabel an installer with a version the " +
            "project does not have."
    )
    val parts = appVersion.split('.')
    if (parts.isEmpty() || parts.size > 3) bad("jpackage takes one to three dot-separated integers.")
    val nums = parts.map { it.toIntOrNull() ?: bad("'$it' is not an integer.") }
    if (nums.any { it < 0 }) bad("negative components are not allowed.")
    // macOS: observed, see above.
    if (hostIsMac && nums[0] < 1) bad("macOS requires the FIRST number to be 1 or greater (CFBundleVersion).")
    if (hostIsWindows) {
        if (nums[0] > 255) bad("MSI requires MAJOR in 0..255.")
        if (nums.size > 1 && nums[1] > 255) bad("MSI requires MINOR in 0..255.")
        if (nums.size > 2 && nums[2] > 65535) bad("MSI requires BUILD in 0..65535.")
    }
}

/**
 * MSI upgrade code. THIS VALUE MUST NEVER CHANGE.
 *
 * Windows Installer identifies "the same product across versions" by this GUID alone.
 * Change it and a new version installs ALONGSIDE the old one instead of replacing it,
 * leaving two OSHI entries in Add/Remove Programs and two copies on disk. Generated once,
 * here, and checked in on purpose so no release can generate a fresh one.
 */
val windowsUpgradeUuid = "2BE511C0-BF76-4A32-A057-BF20B2FF7496"

/** Placeholder — a real .deb release MUST override this with a monitored address. */
val debMaintainer: String = providers.gradleProperty("debMaintainer").orNull ?: "desktop@oshi.invalid"

val jpackageInputDir = layout.buildDirectory.dir("jpackage/input")
val jpackageOutputDir = layout.buildDirectory.dir("jpackage/out")

/**
 * The jar's own manifest carries the runtime classpath.
 *
 * What jpackage 17 ACTUALLY does with `--input` was checked rather than assumed, by
 * reading the launcher config it generated (`OSHI.app/Contents/app/OSHI.cfg`):
 *
 *     app.classpath=$APPDIR/OSHI-Desktop-0.0.1.jar
 *     app.mainclass=com.oshi.desktop.MainKt
 *     app.classpath=$APPDIR/annotations-13.0.jar
 *     app.classpath=$APPDIR/bcprov-jdk18on-1.76.jar
 *     app.classpath=$APPDIR/json-20231013.jar
 *     app.classpath=$APPDIR/kotlin-stdlib-2.2.20.jar
 *
 * — every jar in the input directory, not just `--main-jar`. So this attribute is NOT
 * what makes the packaged app find BouncyCastle; the .cfg is. It is set anyway, for two
 * reasons that survive being written down: it makes the staged directory runnable
 * directly (`java -jar build/jpackage/input/OSHI-Desktop-*.jar`), and it means the
 * packaged classpath does not rest solely on jpackage behaviour that is not in its
 * documented contract and has changed across JDK releases. The names are bare filenames
 * because they resolve relative to the jar's own directory — exactly the flat layout
 * `--input` produces.
 *
 * The proof that one of the two works is not this comment: it is the packaged app image
 * being LAUNCHED and reproducing the iOS crypto vectors (which needs BouncyCastle's
 * X25519). That is what the "Launch the packaged app image" step in CI does, and what
 * was run by hand on macOS on 2026-08-25.
 */
tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "com.oshi.desktop.MainKt",
            "Implementation-Title" to appName,
            "Implementation-Version" to appVersion,
            // A lazy provider, not `.get()`: resolving the runtime classpath at
            // CONFIGURATION time would make every invocation — `./gradlew tasks`
            // included — need the dependency cache or the network.
            "Class-Path" to configurations.runtimeClasspath.map { cp -> cp.joinToString(" ") { it.name } },
        )
    }
}

/** The flat directory jpackage packages: our jar plus every runtime dependency. */
val jpackageInput by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the app jar and its runtime dependencies for jpackage."
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(jpackageInputDir)
}

/**
 * The host OS, from `os.name` alone.
 *
 * Not `org.gradle.internal.os.OperatingSystem`: it is in Gradle's `internal` package,
 * which carries no compatibility promise, and the answer needed here is three booleans.
 * Same test as `DesktopPaths` uses at runtime, so the build and the app agree.
 */
val hostOsName: String = System.getProperty("os.name").orEmpty()
val hostIsWindows: Boolean = hostOsName.lowercase().contains("win")
val hostIsMac: Boolean = hostOsName.lowercase().let { it.contains("mac") || it.contains("darwin") }
val hostIsLinux: Boolean = !hostIsWindows && !hostIsMac

/**
 * Locate jpackage in the JDK that is running Gradle.
 *
 * Deliberately not a toolchain lookup: the point is to package with the same JDK that
 * compiled the code, and to fail with a sentence a human can act on if that JDK is a JRE
 * or predates 14, rather than with "command not found".
 */
fun jpackageBinary(): File {
    val exe = if (hostIsWindows) "jpackage.exe" else "jpackage"
    val f = File(File(System.getProperty("java.home"), "bin"), exe)
    if (!f.isFile) {
        throw GradleException(
            "no jpackage at ${f.absolutePath}. Gradle is running on ${System.getProperty("java.version")}; " +
                "jpackage ships in the JDK (not the JRE) from 14 onward. Point JAVA_HOME at a JDK 17+."
        )
    }
    return f
}

/**
 * Register one native-installer task.
 *
 * @param requiresOs the host OS this installer type can be built on. jpackage cannot
 *        cross-build; asking for a .msi on a Mac must say so in one sentence rather than
 *        be quietly skipped, because a skipped packaging task in a CI matrix is how a
 *        release ships with one platform's installer missing and nothing red anywhere.
 */
fun registerJpackage(
    taskName: String,
    type: String,
    requiresOs: String,
    extraArgs: List<String> = emptyList(),
) = tasks.register<Exec>(taskName) {
    group = "distribution"
    description = "Builds a $type installer with jpackage (requires a $requiresOs host)."
    dependsOn(jpackageInput)
    // Never up-to-date: the output is an installer whose filename encodes a version, and
    // a stale installer is the worst possible thing to hand a release.
    outputs.upToDateWhen { false }

    doFirst {
        val hostOk = when (requiresOs) {
            "Windows" -> hostIsWindows
            "Linux" -> hostIsLinux
            "macOS" -> hostIsMac
            else -> true
        }
        if (!hostOk) {
            throw GradleException(
                "$taskName builds a $type, which jpackage can only produce on a $requiresOs host " +
                    "(this is $hostOsName). jpackage does not cross-build; run this task in the " +
                    "$requiresOs leg of the CI matrix."
            )
        }
        requireLegalAppVersion(taskName, type)
        val outDir = jpackageOutputDir.get().asFile
        outDir.mkdirs()
        // jpackage has no --overwrite and REFUSES to write over an existing app image
        // ("directory ... is not empty"), so a second run of this task would fail for a
        // reason that has nothing to do with the build. Installers overwrite themselves;
        // only the unpacked image needs clearing.
        if (type == "app-image") {
            File(outDir, if (hostIsMac) "$appName.app" else appName).deleteRecursively()
        }
        val mainJar = tasks.jar.get().archiveFileName.get()
        commandLine(
            listOf(
                jpackageBinary().absolutePath,
                "--type", type,
                "--name", appName,
                "--app-version", appVersion,
                "--input", jpackageInputDir.get().asFile.absolutePath,
                "--main-jar", mainJar,
                "--main-class", "com.oshi.desktop.MainKt",
                "--dest", outDir.absolutePath,
                "--vendor", "OSHI",
                "--description", "OSHI encrypted messenger — desktop client",
            ) + extraArgs
        )
        logger.lifecycle("[jpackage] ${commandLine.joinToString(" ")}")
    }
}


/**
 * The platform's jpackage `--resource-dir`, but ONLY if it holds an actual override.
 *
 * `platform/windows/packaging` and `platform/linux/packaging` exist in the repository with a
 * README explaining what may go in them, and nothing else. Passing an override directory that
 * contains no override is not neutral: jpackage 17 logs about the directory it was handed, and
 * a reader then cannot tell "we deliberately override nothing" from "our override silently did
 * not apply". So the flag is passed only when there is something to apply.
 *
 * The README is excluded by name for the same reason it is allowed to sit there at all:
 * jpackage matches overrides by EXACT filename and ignores everything else, so the README is
 * inert to jpackage — but it must not be what makes this function say "there are overrides".
 */
fun resourceDirArgs(platformDir: String): List<String> {
    val dir = layout.projectDirectory.dir("platform/$platformDir/packaging").asFile
    val overrides = dir.listFiles()?.filterNot { it.name == "README.md" || it.isHidden }.orEmpty()
    return if (overrides.isEmpty()) emptyList()
    else listOf("--resource-dir", dir.absolutePath)
}

/**
 * What each platform's `packaging/` directory would contribute to jpackage — on ANY host.
 *
 * `resourceDirArgs` is evaluated at CONFIGURATION time and its effect only shows up inside a
 * jpackage command line that, for `.msi` and `.deb`/`.rpm`, can never be built on a developer's
 * Mac. That makes it exactly the kind of wiring that is easy to get wrong and impossible to
 * notice: an override that is silently not passed looks identical to an override that is
 * passed and ignored. This task makes the answer observable everywhere.
 *
 *     ./gradlew packagingOverrides
 */
tasks.register("packagingOverrides") {
    group = "distribution"
    description = "Prints the jpackage --resource-dir each platform would contribute, and why."
    val windows = resourceDirArgs("windows")
    val linux = resourceDirArgs("linux")
    doLast {
        listOf("windows" to windows, "linux" to linux).forEach { (name, args) ->
            val dir = layout.projectDirectory.dir("platform/$name/packaging").asFile
            if (args.isEmpty()) {
                logger.lifecycle("$name: no overrides in ${dir.path} — jpackage uses its own templates")
            } else {
                val files = dir.listFiles()?.filterNot { it.name == "README.md" || it.isHidden }
                    .orEmpty().sortedBy { it.name }.joinToString(", ") { it.name }
                logger.lifecycle("$name: --resource-dir ${dir.path}  [$files]")
            }
        }
    }
}

/**
 * Windows .msi.
 *
 * `--win-console` is not cosmetic. Today's entry point is an interactive REPL
 * (`--client`, `--mesh`); a jpackage launcher without it is a GUI subsystem binary with
 * NO console attached, so the REPL reads EOF on its first line and exits — which looks
 * exactly like a crash on startup. This is the same failure the `run` task's
 * `standardInput` line above exists to prevent, one layer down. When Tier 1 lands a real
 * GUI, this flag comes off in the same commit as the windowed entry point, not before.
 */
val packageMsi = registerJpackage(
    "packageMsi", "msi", "Windows",
    listOf(
        "--win-console",
        "--win-menu", "--win-menu-group", appName,
        "--win-shortcut",
        "--win-dir-chooser",
        "--win-per-user-install",          // no UAC prompt, and no need for an admin runner
        "--win-upgrade-uuid", windowsUpgradeUuid,
    ) + resourceDirArgs("windows"),
)

val packageDeb = registerJpackage(
    "packageDeb", "deb", "Linux",
    listOf(
        "--linux-package-name", linuxPackageName,
        "--linux-app-category", "net",
        "--linux-menu-group", "Network",
        "--linux-shortcut",
        "--linux-deb-maintainer", debMaintainer,
    ) + resourceDirArgs("linux"),
)

val packageRpm = registerJpackage(
    "packageRpm", "rpm", "Linux",
    listOf(
        "--linux-package-name", linuxPackageName,
        "--linux-app-category", "Applications/Internet",
        "--linux-menu-group", "Network",
        "--linux-shortcut",
        "--linux-rpm-license-type", "Proprietary",
    ) + resourceDirArgs("linux"),
)

/**
 * macOS .dmg. Not a shipping target for this port — Mac users have the Catalyst app —
 * but it is the one installer type this project's own machine can actually BUILD, which
 * makes it the only local proof that the task graph, the staged input directory and the
 * Class-Path manifest are real rather than plausible.
 */
val packageDmg = registerJpackage(
    "packageDmg", "dmg", "macOS",
    listOf("--mac-package-identifier", "com.oshi.desktop"),
)

/**
 * The unpacked application image, on ANY host.
 *
 * This is the cheap smoke test: it needs no WiX, no rpm-build and no code signing, and
 * the thing it produces can be LAUNCHED. `packageMsi` succeeding proves an installer was
 * written; launching the app image proves the packaged app can start at all, which is the
 * part the Class-Path manifest gets wrong silently.
 */
val packageAppImage = registerJpackage("packageAppImage", "app-image", "any")

tasks.register("packageNative") {
    group = "distribution"
    description = "Builds this host's native installer(s): .msi on Windows, .deb + .rpm on Linux, .dmg on macOS."
    when {
        hostIsWindows -> dependsOn(packageMsi)
        hostIsLinux -> dependsOn(packageDeb, packageRpm)
        hostIsMac -> dependsOn(packageDmg)
        else -> doLast { throw GradleException("no packaging recipe for $hostOsName") }
    }
}
