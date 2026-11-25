# Homematic IP Cloud Binding for openHAB

Connect Homematic IP devices to openHAB through an internet-connected access point and the Homematic IP cloud. Devices appear as openHAB Things. For example, a window contact can trigger a heating rule, and a Rollershutter Item can control a blind.

**Compatibility.** openHAB 5.0.0 or newer, with Java 21. Available channels depend on what each device reports through the unofficial Homematic IP cloud interface.

[Download the latest release and SHA256SUMS](https://github.com/SirDocBrown/openhab-binding-homematicipcloud/releases/latest) · [Build and tests](docs/DEVELOPMENT.md)

## Install and pair an access point

1. Download `org.openhab.binding.homematicipcloud-<version>.jar` from the release assets (for example, `org.openhab.binding.homematicipcloud-0.4.1.jar`). To verify it, download `SHA256SUMS` into the same folder and run `shasum -a 256 -c SHA256SUMS` on macOS or `sha256sum -c SHA256SUMS` on Linux.
2. Copy the JAR into your openHAB add-ons folder. With Docker or QNAP Container Station, use the host folder mounted at `/openhab/addons`. The container user must be able to open the folder and read the file.
3. In **Settings → Things → + → Homematic IP Cloud**, add a **Homematic IP Access Point** bridge. Enter the 24-character SGTIN printed on the access point and the system PIN if configured. Leave the token empty for initial pairing.
4. When the bridge requests confirmation, briefly press the system button on the access point. The pairing window lasts two minutes. The bridge saves the confirmed token in its configuration.
5. When the bridge is **ONLINE**, start a scan under **Settings → Things → + → Homematic IP Cloud**. Add a device from the Inbox, open its **Channels** tab and link the channel you want to an Item.

For an HmIP-SWDM window contact, link `ch1_windowState` to a Contact Item. Open and close the window to confirm that the Item switches between `OPEN` and `CLOSED`.

## Discovery and state updates

The default scan lists end devices. To include heating or switching groups, enable **Discover Groups** on the bridge and scan again. Existing groups continue to receive updates when group discovery is switched off. You can also add a device or group manually using its cloud ID.

The bridge reads one snapshot when it connects or reconnects. During a healthy connection, device and group changes arrive by WebSocket. Opening dashboards, refreshing Items and scanning the Inbox use cached data. The bridge refreshes the full state about every six hours (randomly staggered by up to 30 minutes either way). Malformed device or group events share one earlier refresh, at least 15 minutes after the last snapshot. Device commands also use REST.

After HTTP 429, the bridge pauses all cloud traffic for at least 15 minutes and honours longer Retry-After values up to six hours. It remembers the deadline across restarts and discards commands during the pause. Rejected commands restore the channel’s last known state. Other clients using the same installation must observe the pause separately.

## Channels

The available channels depend on the capabilities reported by the entity. Device channel IDs use the functional channel index and cloud attribute name. Group channels use the prefix `group_`.

| Capability | Item type | Operations |
| --- | --- | --- |
| Window state | Contact | OPEN or CLOSED. A tilted window maps to OPEN. The advanced detail channel carries the original state. |
| Low battery, unreachable | Switch | Read-only warnings |
| Shutter | Rollershutter | UP, DOWN, STOP and percentage position |
| Blind slats | Dimmer | Percentage, preserving the reported shutter position |
| Switch and dimmer | Switch, Dimmer | ON/OFF and brightness as supported |
| Signal light | String, Dimmer | Preset colour and brightness |
| Heating group | Number, Switch, String | Setpoint, boost, heating mode and available profiles |
| Other reported scalar attributes | Number, Switch, String | Read-only raw cloud values |

Routing, radio, restart and device diagnostics are marked as advanced channels. Existing links, including links to advanced channels, retain their channel UIDs on upgrade.

## Heating groups

Link `group_activeProfile` to a **String** Item. Send a profile key reported by that group, such as `PROFILE_1`. If you previously linked a Number Item, replace it with a String Item and relink the same channel ID. Profile availability depends on the group.

The heating mode channel reports `AUTOMATIC`, `MANUAL` and `ECO`. The binding sends `AUTOMATIC` and `MANUAL` to the control-mode endpoint. ECO is displayed as reported by the cloud. Temperature commands accept compatible units and convert them to degrees Celsius.

## File configuration

For an already paired access point, supply the token in the bridge configuration. Replace the example serial, cloud ID and token with your own values.

```java
Bridge homematicipcloud:accesspoint:home "Access Point" [ sgtin="000000000000000000000001", token="YOUR_TOKEN", discoverGroups=false ] {
    Thing device kitchen "Kitchen Window" [ cloudId="000000000000000000000002" ]
}
```

```java
Contact KitchenWindow "Kitchen Window" { channel="homematicipcloud:device:home:kitchen:ch1_windowState" }
```

## Upgrades and troubleshooting

- **Upgrading from an earlier version.** Move the previous JAR outside the add-ons folder and copy the new one into place with the same read permissions. Keep the existing bridge and Things. If `group_activeProfile` was linked to a Number Item, replace that link with a String Item on the same channel. Once the bridge is online, scan again to refresh the Inbox.
- **“Error reading bundle content”.** Check read access from inside the container. For a container named `openhab`, run the command below on the Docker host. If it reports “Permission denied”, check permissions and share ACLs on the mounted host folder and JAR.
- **Pairing timed out.** Disable and re-enable the bridge, then press the access point system button when prompted.
- **Missing channel or command.** Include the device model, binding and openHAB versions, channel ID and observed behaviour in your issue. Remove tokens, PINs, serial numbers and personal device IDs from logs and screenshots.

```sh
docker exec -u openhab openhab sha256sum /openhab/addons/org.openhab.binding.homematicipcloud-0.4.1.jar
```

## Licence and protocol references

This project is licensed under the Eclipse Public License 2.0. The ioBroker adapter and the Homematic IP REST API project document related cloud integrations.

[Licence](LICENSE) · [ioBroker.hmip](https://github.com/iobroker-community-adapters/ioBroker.hmip) · [homematicip-rest-api](https://github.com/hahn-th/homematicip-rest-api)
