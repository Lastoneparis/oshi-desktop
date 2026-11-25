# OSHI Desktop — production validation

This is the release gate for the Windows and Linux installers. A green JVM suite is
necessary, but it does not prove operating-system integrations, microphones, cameras,
Bluetooth radios or installers. Do not describe a build as production-ready until every
applicable item below has an attached test record.

## Windows 11

1. Install the `.msi` as a standard user; confirm OSHI appears in Start, Apps and the
   uninstaller. Upgrade from the previous MSI and confirm data remains available.
2. Start OSHI twice. Confirm the same address remains after restart and the process uses
   Windows DPAPI; then confirm a copied data directory cannot be opened by another Windows
   account.
3. Pair with iOS/macOS and Android using: pasted key, phone scanning Windows' QR, and a
   screenshot/photo scanned by Windows. Confirm malformed QR images are refused.
4. Send and receive text, image, file, group message, edit/delete, receipt and reaction
   with each mobile platform. Sleep/resume Windows and check message notification behaviour.
5. With a microphone, speaker, webcam and NAT-restricted network, run Windows↔iOS and
   Windows↔Android audio/video calls. Record device selection, mute, camera pause, TURN
   fallback, reconnect and teardown results.
6. Attach a Meshtastic node over supported transport and record model, firmware, region,
   send/receive, relay and duplicate-handling results. Do not mark BLE supported without a
   BLE test.
7. Verify Authenticode signature and timestamp on every `.exe` and `.msi` with
   `Get-AuthenticodeSignature`; reject `NotSigned`.

## Debian/Ubuntu and Fedora/RHEL

1. Install the `.deb` using `apt install ./…deb` and the `.rpm` using `dnf install ./…rpm`.
   Confirm the desktop launcher, icon, uninstall and upgrade work without root-owned user data.
2. Confirm the Secret Service backend works in a normal logged-in desktop session; a copied
   data directory must not decrypt under a different account. Test the named passphrase path
   on a headless session.
3. Repeat the QR, relay, group, media, notification, LoRa and call checks above on one
   GNOME and one KDE system.
4. Verify repository metadata is OpenPGP-signed, its signing key fingerprint is documented,
   and an expired or rotated key fails safely.

## Release evidence

For each release retain: installer SHA-256, SBOM/provenance attestation, signed CI run URLs,
OS version, hardware used, peer app versions, network topology, test date, tester and outcome.
Any skipped hardware row is a release blocker for the feature it covers, not a passing result.
