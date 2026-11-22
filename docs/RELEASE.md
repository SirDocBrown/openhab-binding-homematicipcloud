# Homematic IP Cloud v0.2.1

This community release targets openHAB 5.3.0.M5 with Java 21. The JAR filename includes the Maven reactor version, `5.3.0-SNAPSHOT`.

- Discover end devices by default. Enable **Discover Groups** on the bridge to include heating and switching groups in a scan.
- Find window state, low battery and reachability first on a window contact. Technical diagnostics are marked as advanced channels.
- Upgrade an existing installation while retaining device IDs, channel IDs and Item links, including `ch1_windowState`.

Download the JAR and `SHA256SUMS` from the release assets. Verify the checksum and copy the JAR into the openHAB add-ons folder.

The Maven regression test passed, and Karaf resolved the binding against the openHAB 5.3.0.M5 feature catalogue. [Build and test details](https://github.com/SirDocBrown/openhab-binding-homematicipcloud/blob/main/docs/DEVELOPMENT.md)
