package com.oshi.desktop.lora

import java.io.File

/**
 * USB serial attachment — the desktop's answer to the phones' BLE, and the one a person
 * actually has in front of them: the radio is plugged into the Mac.
 *
 * [LoRaAttach] tabulated three transports and picked TCP because it "costs no dependency",
 * recording USB serial as needing "a serial library, e.g. jSerialComm". That table was
 * right about the JDK and wrong about the conclusion, and this file is the correction.
 *
 * ============================================================ WHY NO DEPENDENCY IS NEEDED
 *
 * On macOS and Linux a USB serial port is a **character device in the filesystem**
 * (`/dev/cu.usbmodem21201`, `/dev/ttyACM0`). `read(2)` and `write(2)` on it are ordinary
 * file reads and writes, which `java.io` has done since 1.0. A serial library is needed for
 * exactly ONE thing the JDK cannot do: the `termios` ioctl that sets line discipline.
 *
 * And that one thing has a POSIX command-line interface — `stty` — which is in the base
 * install of both operating systems. So the split is:
 *
 *  - **line discipline** → one `stty` invocation, [sttyCommand];
 *  - **the bytes** → `java.io` on the device file.
 *
 * Zero dependencies, and the `ToRadio`/`FromRadio` protobufs and [LoRaAttach.StreamFraming]
 * are byte-identical to the TCP path — Meshtastic frames its serial stream exactly the same
 * way. Windows is NOT covered: `COM3` is not a file you can open this way and has no
 * `stty`. Windows keeps TCP, and [isSupported] says so rather than failing at open time.
 *
 * ============================================================ RAW MODE IS NOT OPTIONAL
 *
 * A tty's default line discipline is built for terminals, and every one of its defaults
 * corrupts a binary protocol:
 *
 *  - `echo` writes everything we send back at us, which the framer would then parse as
 *    inbound frames;
 *  - `icrnl`/`onlcr` rewrite 0x0D ↔ 0x0A — a one-byte corruption inside a protobuf;
 *  - `ixon`/`ixoff` treat 0x11 and 0x13 as flow control and EAT them;
 *  - `isig` turns 0x03 into SIGINT;
 *  - `crtscts` waits for a CTS line a USB CDC device does not drive, so the first write
 *    blocks for ever.
 *
 * `raw` clears the first four groups in one word; the rest are named explicitly because
 * `raw` is not identically defined across the two platforms and this protocol cannot
 * absorb a single flipped byte. `clocal` is what stops `open` waiting for a carrier that a
 * USB device never asserts.
 *
 * ============================================================ ORDER OF OPERATIONS
 *
 * The device is OPENED FIRST and configured second ([LoRaChannel.Serial]). On macOS a
 * cu device reverts to its initial termios when the last descriptor closes, so an `stty`
 * run before the open would be undone by it. Running it while our own descriptor is open
 * applies the settings to the live terminal.
 *
 * On macOS, use `/dev/cu.*` and never `/dev/tty.*`: the `tty` form blocks in `open` until
 * carrier detect, which is why a reader on it looks like a dead radio.
 */
object LoRaSerial {

    /** Meshtastic's serial rate. A USB CDC device ignores it, a real UART does not. */
    const val DEFAULT_BAUD = 115_200

    /** Only POSIX: a Windows `COM` port is not a file and has no `stty`. */
    fun isSupported(osName: String = System.getProperty("os.name") ?: ""): Boolean =
        !osName.lowercase().startsWith("windows")

    /**
     * Device-name prefixes under `/dev` that a Meshtastic node appears as.
     *
     * `cu.` (macOS, "call up") rather than `tty.`; `ttyACM` is the CDC-ACM class the
     * nRF52 and ESP32-S3 boards present, `ttyUSB` the FTDI/CP210x bridges on the
     * ESP32 boards. Deliberately a prefix list and not a regex over everything in `/dev`:
     * offering a person `/dev/cu.Bluetooth-Incoming-Port` as a radio is how a support
     * thread starts.
     */
    val DEVICE_PREFIXES = listOf("cu.usbmodem", "cu.usbserial", "cu.SLAB_USBtoUART", "ttyACM", "ttyUSB")

    data class Device(val path: String, val name: String)

    /**
     * What is plugged in right now.
     *
     * Listing `/dev` is the whole discovery mechanism: there is no enumeration API to ask,
     * and a name is all a person needs to recognise their own board. Sorted so the list
     * does not reshuffle between two refreshes of a dropdown.
     */
    fun listDevices(dir: File = File("/dev")): List<Device> {
        val names = dir.list() ?: return emptyList()
        return names
            .filter { n -> DEVICE_PREFIXES.any { n.startsWith(it) } }
            .sorted()
            .map { Device(path = File(dir, it).path, name = it) }
    }

    /**
     * The validation boundary, the twin of [LoRaAttach.tcpEndpoint].
     *
     * A device path is handed to a PROCESS, so this is stricter than a hostname check: the
     * path must be absolute, must sit directly in `/dev`, and may contain only characters
     * a device name actually uses. That excludes a separator, a shell metacharacter, `..`
     * and a control character by construction rather than by escaping — the argument is
     * passed as an `exec` element and never through a shell, and this keeps it true even
     * if someone later changes how it is run.
     */
    fun devicePath(raw: String): String? {
        val p = raw.trim()
        if (p != raw || p.isEmpty() || p.length > 128) return null
        if (!p.startsWith("/dev/")) return null
        val name = p.removePrefix("/dev/")
        if (name.isEmpty() || name.length > 100) return null
        if (!name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
        if (name.startsWith(".")) return null
        return p
    }

    /** `stty` reads the device from `-f` on BSD/macOS and `-F` on Linux. */
    fun deviceFlag(osName: String = System.getProperty("os.name") ?: ""): String =
        if (osName.lowercase().contains("mac") || osName.lowercase().contains("darwin")) "-f" else "-F"

    /**
     * The exact argv that puts a tty into a state this protocol survives.
     *
     * Pure, and returned as a list rather than a string, so the unit test asserts the
     * flags a radio depends on and no shell ever sees it. `min 1 time 0` is what makes a
     * `read` block until at least one byte exists instead of returning 0 in a spin.
     */
    fun sttyCommand(path: String, baud: Int = DEFAULT_BAUD, osName: String = System.getProperty("os.name") ?: ""): List<String> =
        listOf(
            "stty", deviceFlag(osName), path,
            baud.toString(),
            "raw",
            "cs8", "-parenb", "-cstopb",
            "-echo", "-echoe", "-echok", "-echoctl", "-echoke",
            "-ixon", "-ixoff", "-crtscts",
            "clocal",
            "min", "1", "time", "0",
        )
}
