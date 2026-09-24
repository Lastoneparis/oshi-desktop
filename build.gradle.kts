// `java.util.*` cannot be written inline anywhere below: in a Gradle Kotlin DSL script
// `java` resolves to the JavaPluginExtension, not the package, so `java.util.X` fails to
// compile with "Unresolved reference: util". Import instead.
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

plugins {
    kotlin("jvm") version "2.2.20"
    // PARITY.md row 1.1. Justified in full in the COMPOSE MULTIPLATFORM block below —
    // this is the first new Gradle plugin this project has taken, and Working rule 4
    // asks for a reason that survives being written down.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.0"
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
    ?: rootDir.parentFile.resolve("OSHI-Android").takeIf { it.isDirectory }?.absolutePath
    // THE PUBLIC CHECKOUT. In the monorepo the Android tree is a sibling; in the
    // standalone open-source repository the seven shared files are vendored here so the
    // project builds from ONE clone. Without this fallback an outside reader could read
    // the source and not compile it, which is the difference between published and
    // auditable. `shared-sources-match` in CI is what keeps the vendored copy honest.
    ?: rootDir.resolve("shared/OSHI-Android").absolutePath

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

/**
 * The SEVENTH shared file, and the only one outside `network/v2` — PARITY.md row 2.5.
 *
 * `service/LlamaCpp.kt` is the JNI declaration of llama.cpp: four `external fun`s and a
 * `System.loadLibrary("llama_jni")`. It qualifies for the same treatment as the crypto
 * six for exactly the same reason — it has NO imports at all, let alone `android.*` — and
 * it must be shared rather than copied for a reason the crypto files do not have:
 *
 *   **A JNI declaration is half of an ABI.** The C++ side exports symbols named after the
 *   fully-qualified class (`Java_com_oshi_messenger_service_LlamaCpp_generate`) and typed
 *   by the Kotlin signature. A COPY in `com.oshi.desktop` would name different symbols and
 *   need a second shim, or — worse — the same shim with the package edited, which drifts
 *   silently: a renamed parameter type on one side and the other side still links, right
 *   up to the `UnsatisfiedLinkError` at the first call. Compiling the Android file means
 *   the desktop and the phone load a shim built from ONE header.
 *
 * The desktop does NOT share `LocalLLMManager.kt` or `NLPManager.kt`: those are Hilt +
 * `android.content.Context` + ML Kit, and the parts of them worth having (the ChatML
 * prompt, the output cleanup) are re-derived and byte-asserted against the Kotlin in
 * `com.oshi.desktop.ai.OshiPrompt`. The TRIPWIRE above applies to this file too.
 */
val sharedServiceSources = "$oshiAndroidRoot/app/src/main/java/com/oshi/messenger/service"

/**
 * The EIGHTH and NINTH shared files — the post-quantum layer. Added 14/09.
 *
 * WHY. The crypto audit (`ANSSI_CSPN_CRYPTO_AUDIT.md`, Finding 3) found this client
 * shipping NO post-quantum layer at all, while iOS and Android both ship the X-Wing
 * hybrid (ML-KEM-768 + X25519). That is worse than a missing feature: two peers that
 * negotiate a PQ root with a third that cannot is a DOWNGRADE, and a silent downgrade is
 * precisely what an attacker provokes.
 *
 * SHARED, NOT COPIED — and here the TRIPWIRE argument above is at its strongest. X-Wing
 * interop is byte-exact or it is nothing. Had the desktop carried its own copy and
 * produced a public key one byte different from the phone's, NOTHING would fail to
 * compile: both sides would derive different roots and every message would fail to
 * decrypt, silently, in both directions. One implementation cannot drift from itself.
 *
 * `RatchetSecurityMode.kt` carries the negotiation and the fail-closed rule. Like
 * `LlamaCpp.kt` it has no imports whatsoever, so it shares on the same terms.
 *
 * NOT SHARED: `PostQuantumIdentity.kt` — Context + EncryptedSharedPreferences + MasterKey,
 * i.e. Android key storage. The desktop must provide that seam itself, and until it does,
 * this build has the PQ primitive but does not yet publish a PQ prekey. See PARITY.md.
 *
 * REQUIRES BouncyCastle >= 1.81. `org.bouncycastle.pqc.crypto.xwing` does not exist in
 * 1.76, which is what this file pinned until today.
 */
val sharedEncryptionSources =
    "$oshiAndroidRoot/app/src/main/java/com/oshi/messenger/network/encryption"

/*
 * __GIF_PACK_2026_09_23__ The offline GIF + sticker library (1,718 GIFs, 1,454 stickers,
 * 15 categories, 15 languages) that iOS (`OSHI/GifPack`, `OSHI/StickerPack`) and Android
 * (`assets/GifPack`, `assets/StickerPack`) both ship — byte-identical, manifests included.
 * Read from the Android tree like the shared sources, never copied: a second copy is a
 * second thing to forget to update when a pack grows. Packaged under `gifpacks/` on the
 * classpath. A checkout without the Android tree simply builds without the pack, and the
 * picker says so rather than failing.
 */
tasks.named<ProcessResources>("processResources") {
    from("$oshiAndroidRoot/app/src/main/assets") {
        include("GifPack/**", "StickerPack/**")
        into("gifpacks")
    }
}

sourceSets {
    main {
        kotlin.srcDir(sharedV2Sources)
        kotlin.srcDir(sharedServiceSources)
        kotlin.srcDir(sharedEncryptionSources)
        kotlin.include(
            "**/OSHICryptoV2.kt",
            "**/OSHICryptoV2Streaming.kt",
            "**/OSHIRatchetV2.kt",
            "**/V2Session.kt",
            "**/V2FileKeyMessage.kt",
            "**/V2RetryBudget.kt",
            "**/LlamaCpp.kt",
            "**/PostQuantumKEM.kt",
            "**/RatchetSecurityMode.kt",
            // __CALL_RATING_2026_09_22__ The TENTH shared file — PARITY.md, post-call
            // rating. `service/CallRatingPolicy.kt` decides WHETHER to ask someone about
            // a finished call: the minimum length, the 1-in-3 sampling of ordinary calls,
            // the "a call that dropped always asks" override, the cooldowns and the
            // weekly ceiling. It qualifies on the same terms as the crypto six — it has
            // no imports at all, let alone `android.*`.
            //
            // SHARED, NOT COPIED, for a reason this one has and the crypto files do not:
            // the policy is not a wire format, so a copy that drifts would NEVER fail to
            // compile and never fail to decrypt. It would simply mean the desktop asks
            // four times a week while the phones ask five, and nobody would find out from
            // the channel, because the channel shows ratings — not the ones never asked
            // for. A silent divergence in a sampling rule is a poisoned denominator.
            //
            // NOT SHARED: `service/CallRatingManager.kt` — Context + SharedPreferences +
            // android.util.Log, i.e. the storage and logging seams this client owns. See
            // `com.oshi.desktop.call.CallRatingClient`, which holds the same state in
            // `DesktopPaths` and calls straight into this policy.
            "**/CallRatingPolicy.kt",
            // __DEVSYNC_DIRECT_2026_09_22__ The own-device sync core
            // (docs/OSHI_DEVICE_SYNC_DIRECT.md): Noise XXpsk0, framing, diff/merge, linked-device
            // registry, sessions, LAN + relay plumbing. The whole `network/v2/devsync/` package is
            // pure JVM by construction (BouncyCastle + org.json + java.net) — it has to be, because
            // the phone and the desktop must agree byte for byte with the golden vectors in
            // docs/fixtures/devsync/. The platform seams (OkHttp / java.net.http WebSocket, NSD /
            // MdnsService, Room / MessageStore) live OUTSIDE it, in each tree. The TRIPWIRE above
            // applies: an `import android.*` in there breaks this build.
            "devsync/*.kt",
            // ...plus everything this project actually owns:
            "com/oshi/desktop/**",
        )
    }
    test {
        /*
         * The iOS-generated interop vectors, shared rather than copied for the same reason
         * the sources are: a second copy is a second thing to forget to regenerate.
         *
         * These are produced by `OSHITests/XWingVectorDumpTests.swift` running against
         * CryptoKit on a real iOS 26 simulator. Checking this implementation against
         * Apple's ACTUAL OUTPUT is the whole point — a test that compared it to someone's
         * reading of the X-Wing draft would prove nothing, since both could be wrong in
         * the same way.
         */
        resources.srcDir("$oshiAndroidRoot/app/src/test/resources")
    }
}

// =====================================================================================
// COMPOSE MULTIPLATFORM — PARITY.md row 1.1. THE UI FRAMEWORK DECISION, TAKEN.
// =====================================================================================
//
// This is the first new Gradle PLUGIN this project has ever taken, and the first
// dependency that is not either the JDK or one of the two libraries Android pins. So it
// gets the same treatment the WEBRTC block below gets: what was chosen, what was
// rejected, what it costs measured rather than guessed, and what is NOT proven.
//
// THE DECISION: Compose Multiplatform for desktop (JVM), 1.9.0. Default ON, no opt-in
// flag. PLAN.md §1 recommended it and deferred it "until the protocol layer is proven";
// PARITY.md Tier 0 now records text AND media crossing to a shipped Android phone over
// the live relay, so the condition it was deferred on has been met. A flag would have
// been the cowardly version of this decision — an opt-in UI is a UI nobody compiles,
// and this repository already has a row (0.27) about code that is written, green and
// has never moved a byte.
//
// WHY IT WINS, AND THE ONE THING THAT ALMOST MADE IT LOSE
//
// PLAN.md §1's honest caveat about Compose was that "the Android UI is Jetpack Compose
// married to Hilt, Room and Android ViewModels — the screens are not portable as-is."
// That is still true and it is worth being blunt about: **there is no screen reuse
// here.** Not one composable is shared with OSHI-Android. So the usual argument for
// Compose Multiplatform — share the UI — does not apply to this project at all, and if
// that were the only argument this row should have picked something else.
//
// What survives the caveat is narrower and is the actual reason:
//
//   1. **One language across the whole client.** The state layer this UI drives
//      (`com.oshi.desktop.ui.state`) is plain Kotlin talking to `OshiClient` directly —
//      the same objects, the same types, no serialisation boundary, no second model of
//      what a Message is. Swing/JavaFX would have been Kotlin too, so this is not
//      decisive on its own; it matters because of (2).
//   2. **A Kotlin developer reading OSHI-Android's UI can read this one.** The idioms
//      are identical even though the code is not shared: `@Composable`, state hoisting,
//      `remember`, unidirectional data flow. This project's documented failure mode is
//      two implementations drifting apart because nobody could hold both in their head;
//      picking a UI toolkit that at least reads the same on both sides is the cheapest
//      available hedge against that on the one layer where the code genuinely cannot be
//      shared.
//   3. **One artifact for all three targets.** Skiko renders identically on Windows,
//      Linux and macOS. Swing's look and feel does not, and JavaFX is no longer in the
//      JDK — adopting it would mean shipping OpenJFX per platform, which is the same
//      per-platform native problem for a *worse* toolkit.
//
// WHAT WAS REJECTED
//
//   * **Swing.** Zero new dependencies, which under Working rule 4 is a genuine
//     argument and the reason this was not dismissed. Rejected on the honest reading of
//     what the remaining 101 screens in VIEWS.md need: a chat transcript with
//     hover-reveal actions, reactions, inline media and a live-updating list is a lot of
//     custom `paintComponent` and a lot of `SwingUtilities.invokeLater`. Compose's cost
//     is paid once, at the dependency; Swing's is paid per screen, for 101 screens.
//   * **JavaFX / OpenJFX.** Out of the JDK since 11. Adopting it means per-platform
//     native modules — exactly the packaging problem the GStreamer option was rejected
//     for below — for a toolkit with less momentum than Compose.
//   * **An Electron/Tauri shell.** PLAN.md §1 already disposed of this as options (a)
//     and (c): it is the web client, it is not end-to-end encrypted, and wrapping it
//     does not change that.
//
// WHAT IT COSTS, MEASURED (Content-Length off repo1.maven.org, 1.9.0 / skiko 0.9.22.2)
//
//      compose runtime + foundation + ui + material3   ~9 MB of jars, pure Java/Kotlin
//      skiko-awt-runtime-<host>                        ~28 MB unpacked, ONE per host
//
// The skiko native is loaded the same way the WebRTC one is — an ordinary classpath
// resource inside an ordinary jar — so `jpackageInput`, which is a flat `Sync` of the
// runtime classpath, stages it with no change to any packaging task. And like the
// WebRTC native, only the HOST's classifier is resolved: `compose.desktop.currentOs`
// picks it from `os.name`/`os.arch`, so each installer carries one and never all five.
//
// WHAT IS NOT PROVEN, and this is the part that matters most
//
//   * **The window is OPENED on Windows and Linux in CI, and has never been LOOKED AT
//     there.** `package / windows-latest` launches `OSHI.exe --ui` and fails unless the
//     process is still alive 25 seconds later, which is what proves skiko's windows-x64
//     native loads at all; the Linux leg does the same under `xvfb`. That is the whole of
//     the evidence. "Did not exit" is not "renders correctly", and no screenshot of this
//     window on either platform exists.
//   * **No test in this repository opens a window**, deliberately: a UI test that needs
//     a display cannot run on a headless CI runner, and this project's rule is that a
//     guard which has not been watched failing is not a guard. What IS tested is
//     `com.oshi.desktop.ui.state`, which is plain Kotlin with zero `androidx.compose`
//     imports and drives a REAL `OshiClient` over a REAL in-process relay. The
//     composables are a thin renderer over that state and are NOT covered.
//   * The 34 localisation catalogs (row 1.5) now cover the shared strings the window uses;
//     desktop-only facts remain in an explicitly English overlay. CatalogAuditTest asserts
//     the 46 shared keys and the overlay's exact source reachability separately.
//
// =====================================================================================
// WEBRTC — PARITY.md row 2.1. OPT-IN, DEFAULT OFF. Read this before turning it on.
// =====================================================================================
//
// THE FINDING THAT DECIDES THIS ROW: **neither shipped OSHI client uses WebRTC.**
//
// The ledger's own words for row 2.1 were "WebRTC + a codec pipeline", and that premise
// turned out to be wrong in the same way row 0.25's "iOS-only" premise was wrong. What
// the phones actually run was read line by line in both trees:
//
//   * iOS: `OSHI.xcodeproj` has ZERO SwiftPM/CocoaPods/Carthage entries — no Podfile, no
//     Package.resolved, no WebRTC.xcframework. `StunClient.swift:12` says it outright:
//     "No libwebrtc. Pure Network.framework." Every `WebRTC` grep hit in the Swift tree
//     is a comment.
//   * Android: `app/build.gradle.kts:221-235` is a comment block explaining the REMOVAL
//     of `io.getstream:stream-webrtc-android` — "declared here and never used… `grep -rn
//     org.webrtc app/src` returns zero hits".
//   * There is no SDP anywhere in either tree. ICE candidates are a bespoke binary TLV
//     (`[version 0x01][count][type][family][port BE16][addr][priority BE32]`), not
//     `a=candidate:` text. STUN and TURN are hand-rolled against RFC 5389 / 5766.
//   * Media is AAC-ELD / raw PCM / the in-house `OshiCodec`, AES-256-GCM under the
//     offer's 32-byte session key. Not SRTP.
//
// And the project's own direction is explicitly AWAY from WebRTC's codec:
// `CALL_V2_PLAN.md:26-31` specifies a proprietary codec and states the constraint as a
// user requirement — *"Why proprietary: requirement from user. Cannot rely on Opus/AAC."*
//
// So a WebRTC media stack on the desktop would produce a client that **cannot call a
// single shipped OSHI phone.** That is precisely the shape PARITY.md row 0.16 warns
// about — "the obvious reading would produce something no phone can read" — and it is
// why the media path this client actually ships (`com.oshi.desktop.call.media`) is the
// OSHI one: raw PCM 48 kHz in packet type 0x15, sealed with the existing AES-256-GCM
// under the call's session key and directional nonce salts, captured and rendered
// through `javax.sound.sampled`. **Zero new dependencies**, and byte-compatible with
// what an iPhone and an Android phone already decode.
//
// WHY THE DEPENDENCY IS DECLARED AT ALL, AND WHY IT IS OFF
//
// Turning it on buys desktop↔desktop calls with a mature congestion controller, jitter
// buffer, echo canceller and packet-loss concealment — none of which `javax.sound`
// provides and none of which this row writes. That is a real capability and the option
// was verified rather than guessed (see below). It is DEFAULT OFF because Working rule 4
// asks for a reason that survives being written down, and "adds 15 MB of native code per
// installer to talk to nobody the product currently has" does not survive it. Whether
// the desktop ever gets a WebRTC lane is a PRODUCT decision — the same status row 0.26
// gives the bot lane, and recorded here as open rather than taken quietly.
//
// Enable with `-PwithWebRtc=true`. Nothing in `com.oshi.desktop.app` imports it.
//
// WHAT WAS VERIFIED, ON THIS MACHINE, 2026-08-25 (macOS aarch64, JDK 17)
//
//   * the artifact resolves and its native library loads: `new PeerConnectionFactory(
//     new HeadlessAudioDeviceModule())` succeeded in 1 144 ms;
//   * offered audio codecs are opus/48000, red, G722, PCMU, PCMA, CN, telephone-event;
//   * a generated SDP offer is well-formed — 1 344 bytes, `m=audio`, `a=ice-ufrag:`,
//     `a=fingerprint:`, opus present;
//   * **two in-process peer connections completed offer → answer → ICE and both reached
//     `RTCPeerConnectionState.CONNECTED`**, 5 host candidates each, gathering COMPLETE.
//
// WHAT WAS NOT AND CANNOT BE VERIFIED HERE, stated the way row 0.27 states the radio
// link: no audio hardware was opened (the test uses `HeadlessAudioDeviceModule`), no
// call crossed a machine boundary, no NAT was traversed, and no Windows or Linux native
// has ever been loaded — only macos-aarch64 has. Two peers inside one JVM prove the API,
// the DTLS handshake and the ICE loop; they do not prove a call.
//
// WHAT WAS CONSIDERED
//
//   1. `dev.onvoid.webrtc:webrtc-java` — JNI bindings over Google's own libwebrtc.
//      CHOSEN, of the three. Apache-2.0 (`webrtc-java-parent-0.16.0.pom`, <licenses>). Publishes
//      prebuilt natives to Maven Central for windows-x86_64, linux-x86_64,
//      linux-aarch64, linux-aarch32, macos-x86_64 and macos-aarch64 — verified by
//      listing the 0.16.0 directory on repo1, not by reading the README. 0.16.0 was
//      released 2026-08-24, so the project is alive.
//
//   2. GStreamer via `gst1-java-core`. REJECTED on packaging. gst1-java-core is a pure
//      Java JNA binding with NO bundled natives: it needs a SYSTEM GStreamer install
//      plus the `webrtcbin` plugin from gst-plugins-bad. On Linux that is a distro
//      package this build would have to declare as a .deb/.rpm dependency; on Windows
//      it is a separate MSI the user installs by hand, or ~100 MB of DLLs this build
//      would have to redistribute and keep in step. Row 1.6's whole position is that
//      the installer is jpackage and nothing else — an external system runtime breaks
//      that, and it breaks it worst on Windows, which is half the point of this port.
//
//   3. A pure-Java WebRTC stack. THERE ISN'T ONE, and this was checked rather than
//      assumed. What exists in pure Java is the SIGNALLING and ICE half — e.g. ice4j —
//      never the media half: SRTP, the Opus codec, the jitter buffer, echo cancellation
//      and the congestion controller are all native in every shipped implementation.
//      Writing that is not a row, it is a company. Recorded here so nobody re-opens it.
//
// WHAT IT COSTS, MEASURED (Content-Length off repo1.maven.org, 0.16.0)
//
//      webrtc-java-0.16.0.jar                  113 KB   pure Java API, all platforms
//      ...-windows-x86_64.jar                 8.31 MB   → one .dll
//      ...-linux-x86_64.jar                   9.36 MB   → one .so
//      ...-linux-aarch64.jar                  8.37 MB
//      ...-macos-aarch64.jar                  6.32 MB   → one 13.9 MB .dylib
//
// So roughly +8-10 MB compressed on the runtime classpath, ~15 MB unpacked. Only the
// HOST's classifier is added (see [webrtcNativeClassifier]), because jpackage does not
// cross-build anyway — each installer is already produced on its own runner, so each
// installer gets exactly one native and never the other five.
//
// THE TRAP, OBSERVED NOT READ: the plain coordinate resolves to NOTHING.
//
// `implementation("dev.onvoid.webrtc:webrtc-java:0.16.0")` on its own compiles fine and
// then dies at runtime with UnsatisfiedLinkError. The published POM declares its own
// natives as a self-dependency with `<classifier>${platform.classifier}</classifier>`,
// and `platform.classifier` is set by MAVEN PROFILES activated on <os><family>. Gradle
// does not evaluate Maven profiles. It was run: `gradlew dependencies` printed
//
//     \--- dev.onvoid.webrtc:webrtc-java:0.16.0
//
// with no children and no warning — the natives are silently absent. That is exactly
// the shape of failure this repository keeps writing rows about: a green build that
// measured nothing. The classifier below is therefore declared EXPLICITLY, and
// `WebRtcAvailability` fails loudly at runtime rather than reading as "calls are off".
//
// HOW THE NATIVE IS LOADED, and why it survives jpackage.
//
// `dev.onvoid.webrtc.internal.NativeLoader` (read from the -sources jar) does
// `getClassLoader().getResourceAsStream("libwebrtc-java-<os>-<arch>.<ext>")`, copies it
// to a temp file, `System.load`s it, then deletes it on POSIX / `deleteOnExit` on
// Windows. So the native is an ordinary CLASSPATH RESOURCE inside an ordinary jar.
// `jpackageInput` is a flat `Sync` of `tasks.jar` + `configurations.runtimeClasspath`,
// and the natives jar is just another jar on that classpath with a distinct file name —
// it stages and ships with no change to the packaging tasks. Two consequences worth
// knowing before a release: startup writes ~15 MB to the system temp dir every launch,
// and on Windows that file is only reclaimed on a CLEAN JVM exit (`deleteOnExit`), so a
// crash leaks it. Neither is a blocker; both are things a support ticket will ask about.
//
// WHAT DOES NOT WORK: **windows-aarch64 is NOT published.** Windows on ARM gets no
// native and therefore no calls. That is a platform gap, not a footnote — row 2.1 is
// x86_64-only on Windows, and [webrtcNativeClassifier] names it in the failure message
// instead of quietly resolving nothing.

/** Pinned. A different libwebrtc is a media-interop risk, not a housekeeping detail. */
val webrtcVersion = "0.16.0"

/**
 * Is the WebRTC lane compiled in? **Default false** — see the WEBRTC block above.
 *
 * `-PwithWebRtc=true` turns it on. When it is off, `com.oshi.desktop.call.webrtc` is
 * excluded from BOTH source sets, so the default build has exactly the dependencies it
 * had before this row and `WebRtcLane` cannot be referenced by accident from the
 * shipping media path.
 */
val withWebRtc: Boolean = providers.gradleProperty("withWebRtc").orNull?.toBoolean() ?: false

// Compile the WebRTC lane out entirely unless it was asked for. A source set that is
// merely "not called" still has to compile, which would drag the dependency back in
// through the back door and make the default build carry it after all.
if (!withWebRtc) {
    sourceSets {
        main { kotlin.exclude("com/oshi/desktop/call/webrtc/**") }
        test { kotlin.exclude("com/oshi/desktop/call/webrtc/**") }
    }
}

/**
 * The natives classifier for the machine this build is running on.
 *
 * Override with `-PwebrtcNatives=linux-x86_64` when staging a classpath for another
 * platform. Returns null when the host has no published native, and the caller turns
 * that into a NAMED failure — see the WHAT DOES NOT WORK note above.
 */
val webrtcNativeClassifier: String? = providers.gradleProperty("webrtcNatives").orNull ?: run {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val family = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "linux"
    }
    // `os.arch` spellings differ per JVM vendor; these are the ones actually observed.
    val cpu = when (arch) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        "arm", "armv7l" -> "aarch32"
        else -> null
    }
    when {
        cpu == null -> null
        // The one hole in the matrix. Named, not silently resolved to nothing.
        family == "windows" && cpu != "x86_64" -> null
        family == "macos" && cpu == "aarch32" -> null
        else -> "$family-$cpu"
    }
}

// ======================================================================== VIDEO
//
// PARITY.md row 2.1-v. The JVM has a microphone (`javax.sound`) and no camera and no
// H.264, so a video call needs a native library. bytedeco's FFmpeg preset is the one
// used: ONE library gives all three pieces (the OS camera through libavdevice —
// avfoundation / dshow / video4linux2 — an H.264 encoder, and the H.264 decoder), it is
// LGPL (the `-gpl` classifier, which adds libx264, is deliberately NOT used), and it
// publishes natives for macOS arm64/x86_64, Windows x86_64 and Linux x86_64/arm64.
//
// javacv is NOT used: its POM pulls opencv, tesseract, librealsense and eight more
// presets we would never load. The camera/encoder/decoder code talks to the avcodec
// API directly (`call/video/Ffmpeg*.kt`).
//
// Same rule as [webrtcNativeClassifier]: only the HOST's natives are added (jpackage
// does not cross-build), and like webrtc-java the plain coordinate carries no native at
// all — the classifier is declared explicitly. Windows on ARM has no published native.
val bytedecoJavacppVersion = "1.5.12"
val bytedecoFfmpegVersion = "7.1.1-1.5.12"
val bytedecoNativeClassifier: String? = providers.gradleProperty("videoNatives").orNull ?: run {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val cpu = when (arch) {
        "amd64", "x86_64", "x64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }
    when {
        cpu == null -> null
        os.contains("win") -> if (cpu == "x86_64") "windows-x86_64" else null
        os.contains("mac") || os.contains("darwin") -> "macosx-$cpu"
        else -> "linux-$cpu"
    }
}

dependencies {
    // Pinned to EXACTLY what OSHI-Android uses (app/build.gradle.kts:218, :296).
    // A different BouncyCastle is a parity risk, not a housekeeping detail.
    // 1.81, not 1.76: `org.bouncycastle.pqc.crypto.xwing` (X-Wing = ML-KEM-768 + X25519)
    // first ships in 1.81, and the shared PostQuantumKEM.kt needs it. Android pins the
    // same 1.81 — the two MUST match, because a difference in the X-Wing implementation
    // between phone and desktop would surface as undecryptable messages, not as a build
    // error.
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("org.json:json:20231013")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    // PARITY.md row 1.1 — see the COMPOSE MULTIPLATFORM block above for the whole
    // justification. `currentOs` resolves the skiko native for THIS host only, for the
    // same reason [webrtcNativeClassifier] does: jpackage does not cross-build, so an
    // installer that carried all five natives would be carrying four it can never use.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // PARITY.md row 2.1. OPT-IN — see the WEBRTC block above for the whole justification
    // and for why the SHIPPING media path needs none of this.
    //
    // __CALL_APM_2026_09_23__ ALWAYS ON, for ONE class: `dev.onvoid.webrtc.media.audio.
    // AudioProcessing` — WebRTC's audio-processing module (AEC3 echo canceller, noise
    // suppression, AGC2), used by `call/media/EchoControl.kt` on the shipping PCM path. No
    // WebRTC transport, SDP or codec is used: the `call/webrtc/**` sources stay excluded
    // unless -PwithWebRtc=true. Why: the desktop had NO echo control, and a real iPhone
    // call (2026-09-23) heard its own voice back plus the Mac's mic hiss; the iPhone uses
    // Apple's voice processing (`VoiceCallManager.swift:9645`). Where no native exists
    // (windows-aarch64), EchoControl logs it and the call keeps the raw path.
    val nativeClassifier = webrtcNativeClassifier
    implementation("dev.onvoid.webrtc:webrtc-java:$webrtcVersion")
    if (nativeClassifier != null) {
        implementation("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:$nativeClassifier")
    }
    if (withWebRtc && nativeClassifier == null) {
        logger.warn(
            "[webrtc] No published libwebrtc native for os.name='${System.getProperty("os.name")}' " +
                "os.arch='${System.getProperty("os.arch")}'. Upstream publishes windows-x86_64, " +
                "linux-x86_64, linux-aarch64, linux-aarch32, macos-x86_64 and macos-aarch64 — " +
                "notably NOT windows-aarch64. The build proceeds and the SIGNALLING half of " +
                "PARITY.md row 2.1 still compiles and tests; the MEDIA half will refuse to start " +
                "at runtime with a named error (see WebRtcAvailability). Override with " +
                "-PwebrtcNatives=<classifier> if you know better than this check."
        )
    }

    // PARITY.md row 2.1-v — the CAMERA and the H.264 codec. See the VIDEO block below.
    implementation("org.bytedeco:javacpp:$bytedecoJavacppVersion")
    implementation("org.bytedeco:ffmpeg:$bytedecoFfmpegVersion")
    val videoNatives = bytedecoNativeClassifier
    if (videoNatives != null) {
        implementation("org.bytedeco:javacpp:$bytedecoJavacppVersion:$videoNatives")
        implementation("org.bytedeco:ffmpeg:$bytedecoFfmpegVersion:$videoNatives")
    } else {
        logger.warn(
            "[video] No bytedeco FFmpeg native for os.name='${System.getProperty("os.name")}' " +
                "os.arch='${System.getProperty("os.arch")}'. Calls still carry audio; the camera " +
                "and the video decoder report themselves unavailable at runtime. " +
                "Override with -PvideoNatives=<classifier>.",
        )
    }

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

/**
 * Regenerate `src/main/resources/i18n/` from the iOS `.lproj` tree.
 *
 * DELIBERATELY NOT wired into `build`. The 34 catalogs are CHECKED IN, for the reason in
 * `Catalog.kt`'s doc: a Windows or Linux runner has no `OSHI/` checkout, and a generation
 * step that runs only where the iOS tree happens to exist would produce a packaged `.msi`
 * containing zero translations without turning anything red. Generation is a deliberate
 * act by someone holding both trees; `CatalogFreshnessTest` is what notices when they
 * drift, and it SKIPS rather than fails where the iOS tree is absent.
 */
/**
 * Rasterise this window's screens to PNG, with no display — `./gradlew renderScreens`.
 *
 * The composables in `com.oshi.desktop.ui` are covered by no test and that is deliberate
 * (a test needing a display cannot run on the headless runners this project's Windows and
 * Linux evidence comes from). The gap it leaves is the class of defect nobody finds in a
 * diff: a pane that measures to zero height, a Canvas with no width, two labels on top of
 * each other. `ImageComposeScene` renders into a Skia surface with no window and no display
 * server, so this runs anywhere the suite runs — including the two platforms where nobody
 * has ever LOOKED at this window.
 *
 * It is a task and not a test on purpose: rendering proves a composition measures and
 * paints, never that it is correct, and a golden-image assertion on a UI this young would
 * fail on every intentional change until somebody deleted it. See `ScreenRenderer`'s own
 * doc. The blank-frame check it prints is the one piece that IS a judgement — and it reads
 * the mean as well as the standard deviation, because a gate on the spread alone is how a
 * previous campaign shipped 13 black frames.
 */
tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "Renders the window's screens to build/screens as PNG, without opening a window."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.oshi.desktop.ui.ScreenRenderer")
    args(layout.buildDirectory.dir("screens").get().asFile.absolutePath)
    // Skiko picks a software renderer here; there is no window to composite into and the
    // default GPU path wants a surface it will not get on a headless runner.
    systemProperty("skiko.renderApi", "SOFTWARE")
}

tasks.register<JavaExec>("i18nExtract") {
    group = "localisation"
    description = "Re-extract the 34 .lproj catalogs into src/main/resources/i18n (needs -PoshiIosRoot)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.oshi.desktop.i18n.tools.CatalogExtractor")
    args(oshiIosRoot, layout.projectDirectory.dir("src/main/resources").asFile.absolutePath)
}

tasks.test {
    // Lets the tripwire test be pointed at a mutated copy of the Android tree, so the
    // guard can be watched FAILING instead of merely being green.
    systemProperty("oshi.android.root", providers.gradleProperty("oshiAndroidRootOverride").orNull ?: oshiAndroidRoot)
    // The mesh wire contract is defined by TWO shipped implementations, so the parity
    // tests read both trees. Absent either one, those tests skip rather than fail — a
    // Linux build box has no reason to hold the iOS sources.
    systemProperty("oshi.ios.root", providers.gradleProperty("oshiIosRootOverride").orNull ?: oshiIosRoot)

    // PARITY.md row 2.5. Where `libllama_jni.so` / `llama_jni.dll` is, for the ONE test
    // that can only be answered by a JVM: does `System.loadLibrary("llama_jni")` — inside
    // a companion init in a file this project does not own — resolve?
    //
    // It has to be a JVM ARGUMENT and not `systemProperty` set later: `java.library.path`
    // is read once, when the JVM starts, and a value assigned afterwards is ignored in
    // silence. Gradle forks the test JVM, so this lands on that fork's command line.
    //
    // Unset on every developer machine and on every CI job except `native-llama`. Nothing
    // skips as a result — LlamaNativeTest asserts the ABSENT behaviour when the library is
    // absent and the LOADED behaviour when OSHI_EXPECT_LLAMA_NATIVE says one was built.
    providers.gradleProperty("llamaLibDir").orNull?.let { dir ->
        jvmArgs("-Djava.library.path=$dir")
        logger.lifecycle("tests will look for the llama.cpp JNI library in: $dir")
    }

    // SKIKO RENDERS IN SOFTWARE UNDER THE SUITE TOO, not only under `renderScreens`.
    //
    // Tests that rasterise a composition through `ImageComposeScene` need this exactly as
    // much as the screenshot task does, and they did not have it: three MediaViewer render
    // tests failed in the full suite with "magenta pixels found: 0" — the bitmap decoded
    // and never reached the surface — while passing when the same code was driven from
    // `renderScreens`, which sets the property. The defect was invisible to whoever wrote
    // either side: the renderer worked, the tests worked in isolation, and only the
    // integrated run disagreed.
    //
    // It matters more on CI than here. A GitHub runner is headless; the default GPU path
    // wants a surface it will never get, and the failure it produces is a blank frame
    // rather than an exception — which is the shape of failure this project has already
    // shipped once (PARITY.md's 13 black frames).
    systemProperty("skiko.renderApi", "SOFTWARE")

    // `BrandingTest` reads the jpackage resource directories off the FILE SYSTEM, and
    // nothing else in this build does — so without this line Gradle has no idea they are
    // an input and reports `:test UP-TO-DATE` after the Windows icon has been renamed or
    // deleted. That was not a guess: the rename was tried, and the suite did not run.
    // A guard that an incremental build can skip is not a guard.
    inputs.files(
        fileTree("platform") { include("*/packaging/**") }
    ).withPropertyName("packagingResources").withPathSensitivity(PathSensitivity.RELATIVE)
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

/** A monitored OSHI contact, overridable by a downstream package maintainer. */
val debMaintainer: String = providers.gradleProperty("debMaintainer").orNull ?: "contact@oshi-messenger.com"

/**
 * Linux package identity is deliberately explicit rather than inherited from a JDK
 * default.  The Debian maintainer scripts use this path to register the desktop entry;
 * making both sides name it here prevents a JDK upgrade from leaving a package that
 * installs correctly but whose menu entry cannot be removed.
 */
val linuxInstallDir = "/opt/$linuxPackageName"
val oshiWebsite = "https://oshi-messenger.com/"
val licenseFile = layout.projectDirectory.file("LICENSE").asFile

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
            // THE SAME GROUP PREFIX THE STAGING USES, and it must stay in step with it.
            //
            // These are bare filenames resolved relative to the jar's own directory, so
            // they have to name the files that are ACTUALLY staged beside it. When the
            // duplicate-jar fix below started qualifying staged names with their module
            // group, three consumers were updated (`jpackageInput`, `distributions`,
            // `startScripts`) and this one was not — leaving all ~44 entries naming files
            // that no longer exist. `java -jar build/jpackage/input/OSHI-Desktop-*.jar`,
            // the command this manifest exists to support, was broken by that omission,
            // and nothing catches it: CI launches the jpackage launcher, which reads its
            // own `.cfg`, and no job runs `java -jar`.
            "Class-Path" to configurations.runtimeClasspath.map { cp ->
                cp.joinToString(" ") { f -> stagedJarName(f) }
            },
        )
    }
}

/**
 * Two runtime jars share a FILE NAME, and dropping either one is a shipped app that
 * does not start.
 *
 * Compose Multiplatform pulls `org.jetbrains.compose.runtime:runtime-desktop:1.9.0`,
 * which is a RELOCATION SHIM that depends on `androidx.compose.runtime:runtime-desktop:
 * 1.9.0`. Different coordinates, different content, identical file name:
 *
 *   androidx.compose.runtime/…/runtime-desktop-1.9.0.jar   1 458 134 bytes, 640 entries
 *   org.jetbrains.compose.runtime/…/runtime-desktop-1.9.0.jar    416 bytes,   3 entries
 *
 * Flattened into one directory they collide, which is why `installDist` began failing
 * with "Entry lib/runtime-desktop-1.9.0.jar is a duplicate" the first time anyone tried
 * to RUN the app after the UI landed. No test caught it: no test runs `installDist`.
 *
 * **`DuplicatesStrategy.EXCLUDE` is the trap here.** It keeps whichever copy arrives
 * first, and if that is the 416-byte shim the build still SUCCEEDS and produces an
 * installer whose app dies at startup with a missing Compose runtime. A silent, shipping,
 * order-dependent failure is strictly worse than the loud one we have.
 *
 * So both jars are kept and the name is qualified with the module group, which is unique
 * by construction. The map is built from the RESOLVED ARTIFACTS rather than parsed out of
 * the cache directory layout, because that layout is Gradle's private business.
 */
val runtimeJarGroups: Map<String, String> by lazy {
    configurations.runtimeClasspath.get().incoming.artifacts.artifacts.associate { art ->
        val id = art.id.componentIdentifier
        val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
        art.file.absolutePath to (group ?: "")
    }
}

/**
 * THE ONE PLACE A STAGED JAR IS NAMED. Four things must agree on it — the jar manifest's
 * `Class-Path`, `jpackageInput`, the `distributions` copy and `startScripts` — and they
 * drifted once already, which is why the name is computed here and nowhere else.
 */
fun stagedJarName(f: File): String {
    val group = runtimeJarGroups[f.absolutePath].orEmpty()
    return if (group.isEmpty()) f.name else "$group-${f.name}"
}

/** Prefix a staged dependency jar with its group when, and only when, it needs it. */
fun org.gradle.api.file.FileCopyDetails.qualifyDuplicateJarName() {
    name = stagedJarName(file)
}

/** The flat directory jpackage packages: our jar plus every runtime dependency. */
val jpackageInput by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the app jar and its runtime dependencies for jpackage."
    from(tasks.jar)
    from(configurations.runtimeClasspath) { eachFile { qualifyDuplicateJarName() } }
    into(jpackageInputDir)
}

// The same collision breaks `installDist`, `distZip` and `distTar`, which the application
// plugin builds from the same runtime classpath into a flat `lib/`.
distributions {
    named("main") {
        contents {
            eachFile { if (path.startsWith("lib/")) qualifyDuplicateJarName() }
        }
    }
}

/**
 * ...and the start scripts have to be told, or the rename above is a launcher that cannot
 * find its own classes.
 *
 * `CreateStartScripts` bakes a LITERAL classpath into `bin/OSHI-Desktop` — one
 * `$APP_HOME/lib/<file name>` per dependency, fixed at generation time. Renaming the
 * staged files without regenerating it produced a distribution that builds green and then
 * dies on the first run:
 *
 *   Exception in thread "main" java.lang.NoClassDefFoundError: kotlin/jvm/internal/Intrinsics
 *
 * Found by running the app, not by building it — `installDist` succeeded. Only the names
 * are read off this collection, so bare names are what it is given.
 */
tasks.named<CreateStartScripts>("startScripts") {
    classpath = files(
        listOf(tasks.jar.get().archiveFileName.get()) +
            configurations.runtimeClasspath.get().incoming.artifacts.artifacts.map { art ->
                val group = (art.id.componentIdentifier
                    as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
                if (group != null) "$group-${art.file.name}" else art.file.name
            }
    )
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
 * The launcher icon for THIS host's installer, or null where none is checked in yet.
 *
 * Until this existed, every artifact this project produced wore jpackage's generic default
 * — which on Windows is the Java coffee cup, and it is what a user would have seen in the
 * Start menu, on the desktop shortcut, in Add/Remove Programs and in the taskbar. The
 * shipped iOS/macOS app has one icon and this is the same image, resampled from
 * `OSHI/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`. A third client of the same
 * product wearing a stock icon reads as a different program.
 *
 * PER HOST, not per target, for the same reason as [webrtcNativeClassifier]: jpackage does
 * not cross-build, so the only icon a task can ever need is the one for the machine it runs
 * on. Windows wants `.ico`, Linux `.png`, macOS `.icns`; only the `.ico` is checked in,
 * because Windows and Linux are what this port exists for and Linux's menu entry is a
 * smaller lie than Windows' Start menu. Missing file → no `--icon`, exactly as before.
 *
 * The file ALSO sits where jpackage's `--resource-dir` lookup finds it by name (`OSHI.ico`
 * beside `OSHI-Console.ico`), which is what carries the icon into the MSI's Add/Remove
 * Programs entry — `--icon` alone only dresses the launcher .exe. Both mechanisms point at
 * one file on purpose; two icons that could disagree would be worse than none.
 */
val hostIconFile: File? = when {
    hostIsWindows -> layout.projectDirectory.file("platform/windows/packaging/OSHI.ico").asFile
    hostIsLinux -> layout.projectDirectory.file("platform/linux/packaging/OSHI.png").asFile
    else -> layout.projectDirectory.file("platform/macos/packaging/OSHI.icns").asFile
}?.takeIf { it.isFile }

private fun iconArgs(): List<String> =
    hostIconFile?.let { listOf("--icon", it.absolutePath) } ?: emptyList()


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
                // THE INSTALLED APP MUST OPEN THE WINDOW, and until this line existed it
                // did not. jpackage bakes the launcher's arguments at build time; with
                // none, the installed binary ran `main([])`, which falls past --ui, --mesh
                // and --client into the protocol-skeleton walkthrough — it printed a crypto
                // demo and EXITED. Double-clicking OSHI showed a console flash and closed.
                //
                // No test could see this: the suite never runs the packaged launcher, and
                // `packageMsi` succeeding only proves an installer was produced. It was
                // found by asking what a user double-clicking the download would get.
                "--arguments", "--ui",
                "--dest", outDir.absolutePath,
                "--vendor", "OSHI",
                "--description", "OSHI encrypted messenger — desktop client",
            ) + iconArgs() + extraArgs
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
 * TIER 1 HAS LANDED, SO `--win-console` IS GONE — as this comment previously promised
 * it would be, "in the same commit as the windowed entry point, not before". The default
 * launcher now opens the window (`--arguments --ui`), and a GUI-subsystem binary is what
 * a windowed app should be: no console flashes behind the window.
 *
 * The REPL is not lost. It moves to a SECOND launcher, `OSHI-Console`, which keeps
 * `win-console` — because the original reason for that flag is still true: a jpackage
 * launcher without a console makes the REPL read EOF on its first line and exit, which
 * looks exactly like a crash on startup.
 *
 * Two installers are produced, and the difference is convention rather than capability:
 * `.exe` is what a person downloading a messenger expects to double-click, `.msi` is what
 * an IT department deploys. Same app image inside both.
 */
private val windowsConsoleLauncher: File by lazy {
    // jpackage takes a PROPERTIES FILE per extra launcher, not flags. `win-console=true`
    // is the whole point of this one: it is the surface `/send`, `/lora`, `/call` and the
    // rest live on, and it needs a console to read a line from.
    val f = layout.buildDirectory.get().asFile.resolve("jpackage/OSHI-Console.properties")
    f.parentFile.mkdirs()
    // An add-launcher does NOT inherit the main launcher's `--icon`, and jpackage's
    // resource-dir lookup for it is by the LAUNCHER's name (`OSHI-Console.ico`), not the
    // app's. Without this line the second Start-menu entry is the coffee cup while the
    // first one is OSHI — which reads as two unrelated programs from one installer.
    val icon = hostIconFile?.let { "icon=${it.absolutePath.replace("\\", "\\\\")}\n" } ?: ""
    f.writeText(
        """
        win-console=true
        arguments=--client
        description=OSHI encrypted messenger — terminal client
        """.trimIndent() + "\n" + icon
    )
    f
}

private fun windowsInstallerArgs(): List<String> = listOf(
    "--win-menu", "--win-menu-group", appName,
    "--win-shortcut",
    "--win-dir-chooser",
    "--win-per-user-install",          // no UAC prompt, and no need for an admin runner
    "--win-upgrade-uuid", windowsUpgradeUuid,
    "--add-launcher", "OSHI-Console=${windowsConsoleLauncher.absolutePath}",
) + resourceDirArgs("windows")

/** What a person downloading a messenger expects to double-click. */
val packageExe = registerJpackage("packageExe", "exe", "Windows", windowsInstallerArgs())

/** What an IT department deploys. Same app image inside. */
val packageMsi = registerJpackage("packageMsi", "msi", "Windows", windowsInstallerArgs())

val packageDeb = registerJpackage(
    "packageDeb", "deb", "Linux",
    listOf(
        "--about-url", oshiWebsite,
        "--license-file", licenseFile.absolutePath,
        "--install-dir", linuxInstallDir,
        "--linux-package-name", linuxPackageName,
        "--linux-app-release", "1",
        "--linux-app-category", "net",
        "--linux-menu-group", "Network",
        "--linux-shortcut",
        "--linux-deb-maintainer", debMaintainer,
    ) + resourceDirArgs("linux"),
)

val packageRpm = registerJpackage(
    "packageRpm", "rpm", "Linux",
    listOf(
        "--about-url", oshiWebsite,
        "--license-file", licenseFile.absolutePath,
        "--install-dir", linuxInstallDir,
        "--linux-package-name", linuxPackageName,
        "--linux-app-release", "1",
        "--linux-app-category", "Applications/Internet",
        "--linux-menu-group", "Network",
        "--linux-shortcut",
        // Keep repository, RPM metadata and downstream inventory aligned. LICENSE is MIT.
        "--linux-rpm-license-type", "MIT",
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
        hostIsWindows -> dependsOn(packageExe, packageMsi)
        hostIsLinux -> dependsOn(packageDeb, packageRpm)
        hostIsMac -> dependsOn(packageDmg)
        else -> doLast { throw GradleException("no packaging recipe for $hostOsName") }
    }
}

// ============================================================================
// CODE SIGNING — __SIGNING_WIRED_2026_09_22__
// ============================================================================
//
// The long note above says signing is a documented gap. It still is for Windows,
// because the missing piece is a CERTIFICATE and no amount of build script
// conjures one. What changed is that the gap is now a WIRED step that refuses
// loudly instead of a paragraph someone has to remember at release time.
//
// Nothing here invents a credential. Every task reads its material from the
// environment and fails by NAME when it is absent.
//
// Linux is handled outside Gradle, by `sign-desktop.sh`, for one reason: the
// private key must never be on a build host or in CI. It signs on a workstation
// where the OSHI key already lives (ed25519/025F383CDCCA2600, contact@oshi-
// messenger.com) and uploads the results.

/**
 * Authenticode-sign the Windows .exe and .msi.
 *
 * WHAT YOU NEED FIRST, and why this cannot be done today:
 *
 * An OV or EV code-signing certificate issued to Oshi Lab by a CA. Since June
 * 2023 the CA/Browser Forum requires the private key to live on FIPS 140-2
 * Level 2 hardware — a USB token, or a cloud HSM (Azure Trusted Signing,
 * DigiCert KeyLocker, SSL.com eSigner). It CANNOT be a .pfx on disk or in a CI
 * secret, so there is no way to hold it here.
 *
 *   OV  ~EUR 200-400/yr. SmartScreen still warns until the certificate builds
 *       reputation across enough installs.
 *   EV  ~EUR 350-600/yr. SmartScreen trusts it immediately. For a messenger
 *       downloaded by people who were told it is private, "Windows protected
 *       your PC" on first run is the worst possible first impression, so EV is
 *       the one worth paying for.
 *
 * THEN, with the token plugged in or the HSM credentials exported:
 *
 *   ./gradlew signWindows \
 *       -PsignExe=build/installers/OSHI-Desktop-Setup.exe \
 *       -PsignMsi=build/installers/OSHI-Desktop.msi
 *
 * On Windows this shells out to signtool; on macOS/Linux it uses osslsigncode
 * (`brew install osslsigncode`), which produces a byte-identical Authenticode
 * blob and lets the signing happen on the same workstation as the Linux keys.
 *
 * BOTH FILES MUST BE SIGNED. Signing only the .msi leaves the launcher .exe
 * inside it unsigned, and that is the binary SmartScreen actually judges.
 */
tasks.register("signWindows") {
    group = "distribution"
    description = "Authenticode-sign the Windows .exe/.msi. Requires a code-signing certificate."
    doLast {
        val exe = providers.gradleProperty("signExe").orNull
        val msi = providers.gradleProperty("signMsi").orNull
        if (exe == null && msi == null) {
            throw GradleException(
                "Nothing to sign. Pass -PsignExe=<path> and/or -PsignMsi=<path>."
            )
        }
        // The timestamp URL is not optional: without it every signature expires
        // when the certificate does, and already-installed copies start warning.
        val tsa = providers.gradleProperty("signTimestamp").orNull
            ?: "http://timestamp.digicert.com"

        val onWindows = hostIsWindows
        val tool = if (onWindows) "signtool" else "osslsigncode"
        val available = try {
            providers.exec {
                commandLine(if (onWindows) listOf("where", tool) else listOf("which", tool))
                isIgnoreExitValue = true
            }.result.get().exitValue == 0
        } catch (_: Exception) { false }
        if (!available) {
            throw GradleException(
                "$tool is not on PATH. " +
                    if (onWindows) "Install the Windows SDK."
                    else "brew install osslsigncode (or apt install osslsigncode)."
            )
        }

        val certThumb = System.getenv("WIN_CERT_THUMBPRINT")
        val certFile = System.getenv("WIN_CERT_FILE")
        if (certThumb == null && certFile == null) {
            throw GradleException(
                "No code-signing certificate. Set WIN_CERT_THUMBPRINT (hardware token / " +
                    "HSM, the normal case) or WIN_CERT_FILE + WIN_CERT_PASS. " +
                    "See the note above this task for what to buy."
            )
        }

        for (path in listOfNotNull(exe, msi)) {
            val f = file(path)
            if (!f.isFile) throw GradleException("not a file: $path")
            logger.lifecycle("signing $path")
            providers.exec {
                commandLine(
                    if (onWindows) buildList {
                        addAll(listOf("signtool", "sign", "/fd", "SHA256", "/tr", tsa, "/td", "SHA256"))
                        if (certThumb != null) addAll(listOf("/sha1", certThumb))
                        else addAll(listOf("/f", certFile!!, "/p", System.getenv("WIN_CERT_PASS") ?: ""))
                        add(f.absolutePath)
                    } else buildList {
                        addAll(listOf("osslsigncode", "sign", "-h", "sha256", "-ts", tsa))
                        addAll(listOf("-pkcs12", certFile ?: "", "-pass", System.getenv("WIN_CERT_PASS") ?: ""))
                        addAll(listOf("-in", f.absolutePath, "-out", f.absolutePath + ".signed"))
                    }
                )
            }.result.get()
            if (!onWindows) {
                val signed = file(f.absolutePath + ".signed")
                if (signed.isFile) { f.delete(); signed.renameTo(f) }
            }
        }
        logger.lifecycle("Signed. Verify with: osslsigncode verify <file>  (or signtool verify /pa <file>)")
    }
}

/**
 * Says whether the installers on disk carry a signature. Read-only, and it is
 * the check that should gate a release rather than anyone's memory.
 */
tasks.register("verifySignatures") {
    group = "verification"
    description = "Reports whether the built installers are signed. Never modifies anything."
    doLast {
        val dir = file(providers.gradleProperty("installerDir").orNull ?: "build/installers")
        if (!dir.isDirectory) {
            logger.lifecycle("no installer directory at ${dir.path} — nothing to check")
            return@doLast
        }
        var unsigned = 0
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val verdict = when (f.extension.lowercase()) {
                // An .exe is a PE: Authenticode lives in the certificate table,
                // data directory entry 4, and size 0 means no signature.
                "exe" -> if (peHasCertificateTable(f)) "signed" else "UNSIGNED"
                // An .msi is NOT a PE — it is an OLE compound document, and its
                // signature is a stream named \u0005DigitalSignature, not a
                // certificate table. Running the PE check over one reads whatever
                // bytes happen to sit at the PE offsets and reported the live
                // unsigned installer as "signed". A verifier that errs towards
                // "fine" is worse than none, so the two formats are checked apart.
                "msi" -> if (oleHasDigitalSignature(f)) "signed" else "UNSIGNED"
                "rpm" -> if (headBytes(f, 8192).let { b ->
                        listOf("RSA", "PGP").any { m -> String(b, Charsets.ISO_8859_1).contains(m) }
                    }) "signed" else "UNSIGNED"
                // debsigs puts `_gpgorigin` immediately after `debian-binary`.
                "deb" -> if (String(headBytes(f, 2048), Charsets.ISO_8859_1).contains("_gpgorigin"))
                    "signed" else "UNSIGNED (detached .asc is the supported route)"
                else -> return@forEach
            }
            if (verdict.startsWith("UNSIGNED")) unsigned++
            logger.lifecycle("  %-34s %s".format(f.name, verdict))
        }
        // Only signatures OVER an installer count. The exported public key is
        // also a .asc and counting it would overstate the coverage.
        val detached = dir.listFiles()?.count { a ->
            a.name.endsWith(".asc") && File(a.parentFile, a.name.removeSuffix(".asc")).isFile
        } ?: 0
        logger.lifecycle("  detached signatures present: $detached")
        if (unsigned > 0) logger.lifecycle("  $unsigned file(s) carry no embedded signature")
    }
}

/**
 * True when the OLE compound document at [f] carries a `\u0005DigitalSignature`
 * stream. Stream names are stored UTF-16LE in the directory, which can sit
 * anywhere in the file, so this scans rather than seeking.
 */
fun oleHasDigitalSignature(f: File): Boolean = try {
    val needle = "DigitalSignature".toByteArray(Charsets.UTF_16LE)
    f.inputStream().buffered().use { input ->
        val buf = ByteArray(1 shl 20)
        var carry = ByteArray(0)
        var hit = false
        while (!hit) {
            val n = input.read(buf)
            if (n <= 0) break
            val window = carry + buf.copyOf(n)
            hit = indexOfBytes(window, needle) >= 0
            carry = window.copyOfRange(maxOf(0, window.size - needle.size), window.size)
        }
        hit
    }
} catch (_: Exception) { false }

/** First index of [needle] in [haystack], or -1. */
fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

/** The first [n] bytes of [f]. `File.readBytes()` has no size form in Kotlin. */
fun headBytes(f: File, n: Int): ByteArray = f.inputStream().use { input ->
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = input.read(buf, read, n - read)
        if (r <= 0) break
        read += r
    }
    if (read == n) buf else buf.copyOf(read)
}

/** True when the PE at [f] has a non-empty certificate table. */
fun peHasCertificateTable(f: File): Boolean = try {
    val b = headBytes(f, 4096)
    fun u32(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    fun u16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    val pe = u32(0x3c)
    val magic = u16(pe + 24)
    val dirOff = pe + 24 + (if (magic == 0x20b) 112 else 96) + 4 * 8
    u32(dirOff + 4) > 0
} catch (_: Exception) { false }
