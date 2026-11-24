# Development and verification

The binding is a Maven module in the openHAB Add-ons reactor, which supplies the parent POM, compiler settings, formatting rules and static analysis. Pushing a tag matching `v*` builds the binding from that commit, checks formatting, runs JUnit tests and publishes the Maven JAR and SHA-256 checksum as release assets. Pushes to main run the same build.

## Build in the openHAB Add-ons reactor

Install Git, Maven and JDK 21. The first build needs internet access for Maven and npm dependencies. From a common parent directory, check out both projects and copy the binding into the reactor.

```sh
git clone https://github.com/SirDocBrown/openhab-binding-homematicipcloud.git
git clone https://github.com/openhab/openhab-addons.git
cd openhab-addons
git checkout 5.0.0
module=bundles/org.openhab.binding.homematicipcloud
mkdir -p "$module"
cp -R ../openhab-binding-homematicipcloud/src "$module/"
cp ../openhab-binding-homematicipcloud/pom.xml ../openhab-binding-homematicipcloud/README.md "$module/"
```

In `bundles/pom.xml`, add the following entry to `<modules>` alongside the existing Homematic binding.

```xml
<module>org.openhab.binding.homematicipcloud</module>
```

Run the build from the root of `openhab-addons`.

```sh
mvn -pl :org.openhab.binding.homematicipcloud spotless:check package
```

The JAR is written to `bundles/org.openhab.binding.homematicipcloud/target/`. The regression test uses synthetic cloud responses and can run without credentials.

## Versioning and release

Set the binding version in the module POM's own `<version>` element. Its parent version selects the openHAB reactor. Maven writes the binding version into the OSGi bundle manifest, and the cloud client reads it at runtime.

CI runs the command above on Java 21 against openHAB Add-ons 5.0.0. The JUnit suite covers pairing, bridge reconfiguration, child status during pairing, discovery, state mapping, commands and channel migration. Tests use synthetic data and require no Homematic credentials.

To release from `main`, use the exact commit message `Release v<version>`. After the build, tests and version checks succeed, CI tags that commit and publishes its verified assets. An existing tag pushed manually also runs the build and must match the module version. Download `org.openhab.binding.homematicipcloud-<version>.jar` and `SHA256SUMS` from the [latest release](https://github.com/SirDocBrown/openhab-binding-homematicipcloud/releases/latest).

## Source layout

| Class | Responsibility |
| --- | --- |
| `Factory` | Creates bridge and device handlers. |
| `Cloud` | Handles HTTPS requests, pairing, authentication and WebSocket connections. |
| `Access` | Manages the connection, reconnection, device inventory and state updates. |
| `Discovery` | Adds end devices and optional groups to the Inbox for their bridge. |
| `Entity` | Creates and updates the channels of a device or group. |
| `Points` | Maps cloud fields to states and supported commands. |

When adding a capability, include a synthetic functional-channel example that exercises the reported state and any outgoing command. Keep existing channel IDs stable so users retain their Item links.
