package com.oshi.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.oshi.desktop.lora.LoRaAdmin
import com.oshi.desktop.net.AccountDeletionReceipt
import java.io.File

/**
 * __PANE_HARNESS_2026_10_29__ One pane, in a REAL window, driven by a REAL mouse.
 *
 * ============================================================ WHY THIS EXISTS
 *
 * `renderScreens` rasterises a composition and proves it MEASURES and PAINTS. It cannot
 * press anything, so it says nothing about the two questions that actually matter on these
 * two screens: does the region picker open when it is clicked, and does the destructive
 * button arm only when the word is typed. Those are click-time state transitions and a
 * still image is blind to them.
 *
 * The obvious way to answer them — drive the real app — is not available here and the
 * reason is worth writing down rather than repeating as an excuse:
 *
 *   * `/Applications/OSHI.app` is the owner's INSTALLED client, and on this machine it has
 *     been running for half a day. Quitting it to test my own build is not mine to do.
 *   * A second instance against the same vault is worse. `OSHI_HOME` would isolate it, but
 *     a fresh vault mints a master key through `/usr/bin/security` on the login Keychain
 *     (`SecretStore.MacKeychainStore`), and a keychain prompt is already on this screen.
 *     Adding a second modal auth dialog to a machine that has one stuck is how an
 *     unattended session ends up clicking something it should not.
 *
 * Both panes take a HOST interface rather than `OshiClient`, and that was not decoration:
 * it means the screen can be opened with nothing behind it. No vault, no keychain, no
 * account, no relay, no radio — and therefore nothing of the owner's to damage. What is
 * exercised is exactly what the renderer could not reach: Compose's real hit-testing, real
 * recomposition, and the real enabling rules, under a real pointer.
 *
 * What it does NOT prove, and must not be read as proving: that a radio answers, that the
 * server erases anything, or that these panes behave inside the assembled window. The hosts
 * here are fakes and say so.
 *
 *     ./gradlew paneHarness -PharnessPane=lora
 *     ./gradlew paneHarness -PharnessPane=delete
 */
object PaneHarness {

    @JvmStatic
    fun main(args: Array<String>) {
        val which = args.firstOrNull() ?: "lora"
        application {
            val state = rememberWindowState(
                size = DpSize(1180.dp, 980.dp),
                // Top-left, deliberately: the keychain dialog on this machine sits in the
                // middle of the screen, and a window placed under the pointer's way is a
                // window whose clicks can land somewhere else.
                position = WindowPosition(40.dp, 60.dp),
            )
            Window(onCloseRequest = ::exitApplication, state = state, title = "OSHI pane harness — $which") {
                MaterialTheme(colorScheme = OshiTheme.colors, typography = OshiTheme.typography) {
                    when (which) {
                        "delete" -> DeletePane()
                        else -> RadioPane()
                    }
                }
            }
        }
    }

    @Composable
    private fun DeletePane() {
        com.oshi.desktop.ui.components.DeleteAccountPane(
            object : com.oshi.desktop.ui.components.DeleteAccountHost {
                // A receipt with an unreachable store, so pressing it shows the PARTIAL
                // wording — the case a tick would hide. Nothing is deleted anywhere.
                override fun delete(): Result<AccountDeletionReceipt> = Result.success(
                    AccountDeletionReceipt(
                        complete = false,
                        prekeysErased = true,
                        relayEnvelopes = 12,
                        syncItems = 3,
                        inboundBlobs = 4,
                        outboundBlobsLeft = 2,
                        unreachable = listOf("blobs"),
                    ),
                )
            },
        )
    }

    @Composable
    private fun RadioPane() {
        com.oshi.desktop.ui.components.LoRaPane(
            radio = object : com.oshi.desktop.ui.components.LoRaPaneHost {
                override val attached = true
                override fun devices() = listOf(
                    com.oshi.desktop.lora.LoRaSerial.Device("/dev/cu.usbmodem21201", "cu.usbmodem21201"),
                )
                override fun nodes() = listOf(
                    com.oshi.desktop.lora.LoRaNodeRoster.Node(0x766eb396u, "ThinkNode M1-B", 0L),
                )
                override fun links() = emptyMap<String, String>()
                override fun attachSerial(path: String) = true
                override fun attachTcp(host: String) = true
                override fun detach() {}
                override fun send(text: String) = true
                override fun sendOshi(recipient: String, text: String) = true
                override fun link(node: UInt, address: String) =
                    com.oshi.desktop.lora.LoRaNodeBindings.LearnResult.BOUND
                override fun unlink(node: UInt) = true
                override fun prefs() = com.oshi.desktop.store.TransportPreferences.Values()
                override fun setPrefs(values: com.oshi.desktop.store.TransportPreferences.Values) {}
                override fun fixedPosition() = 46.2044 to 6.1432
                override fun setFixedPosition(latitude: Double, longitude: Double) = true
                override fun clearFixedPosition() {}
                override val beaconEnabled = false
                override fun setBeaconEnabled(value: Boolean) = true
                override fun beaconTick() = com.oshi.desktop.lora.LoRaPositionBeacon.Decision.STILL_HERE
                override fun myNodeName() = "OSHI ThinkNode M1 B"
                override fun setOwner(longName: String, shortName: String) = true
                override fun adoptIfFactoryDefault() = true
                override fun traffic() = emptyList<com.oshi.desktop.lora.LoRaTrafficLog.Entry>()
                // UNSET and a blocked reason: the rescue case, which is the state the
                // region control exists for and the one worth putting under a pointer.
                override fun reportedRegion(): LoRaAdmin.Region? = LoRaAdmin.Region.UNSET
                override fun blockedReason() =
                    "The radio has no region set, so it will not transmit. Choose one and try again."
                // Writes NOTHING. No radio is attached to this process.
                override fun setRegion(region: LoRaAdmin.Region, preset: LoRaAdmin.ModemPreset): Boolean {
                    File(System.getProperty("oshi.harness.log") ?: "/dev/null")
                        .appendText("setRegion ${region.name} ${preset.name}\n")
                    return true
                }
                override fun setChannel(name: String, psk: String): Boolean {
                    File(System.getProperty("oshi.harness.log") ?: "/dev/null")
                        .appendText("setChannel '$name'\n")
                    return true
                }
            },
            contacts = emptyList(),
            onCopy = {},
        )
    }
}
