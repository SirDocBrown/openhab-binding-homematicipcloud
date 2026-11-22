# Homematic IP Cloud Binding

Connects openHAB to Homematic IP devices through an internet-connected access point and the Homematic IP cloud. The bridge uses an unofficial cloud interface. The HmIP-HAP2 and HmIP-SWDM have been used together in a real installation.

## Installation

Download the JAR from the project releases or from the `dist` directory. Place it in your openHAB add-ons folder. For Docker installations use the host directory mapped to `/openhab/addons`. Ensure the container user can read the file. For upgrades, move the previous JAR outside the add-ons folder, keep the existing bridge and run discovery once the bridge is online.

[Release downloads](https://github.com/SirDocBrown/openhab-binding-homematicipcloud/releases) · [Build and test details](DEVELOPMENT.md)

## Supported Things and discovery

Create an `accesspoint` bridge. Enter its 24-character SGTIN and the system PIN if configured. For initial pairing, leave the token empty and create the bridge in the UI. Briefly press the access point system button when the bridge requests confirmation. The bridge stores the confirmed token in its configuration.

Once the bridge is online, run discovery. The inbox lists end devices. Enable **Discover Groups** on the bridge and scan again to include groups such as heating and switching groups. Changing this option affects discovery. Existing device and group Things continue receiving updates. A scan refreshes previous suggestions for the same bridge.

Both the `device` and `group` Thing types can also be configured manually using the cloud ID. The bridge refreshes the initial snapshot through WebSocket events and periodically reconciles it with the cloud.

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
