# Development and verification

The binding is a Maven module in the openHAB Add-ons reactor, which supplies the parent POM, compiler settings, formatting rules and static analysis. Pushing a tag matching `v*` builds the binding from that commit, checks formatting, runs JUnit tests and publishes the Maven JAR and SHA-256 checksum as release assets. Pushes to main run the same build.

## Build in the openHAB Add-ons reactor

Install Git and JDK 21. The first build needs internet access for Maven and npm dependencies. From a common parent directory, check out both projects and copy the binding into the reactor.

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
./mvnw -pl :org.openhab.binding.homematicipcloud spotless:check package
```

The JAR is written to `bundles/org.openhab.binding.homematicipcloud/target/`. The regression test uses synthetic cloud responses and can run without credentials.

## Verification of v0.2.1

The release was compiled with Java 21 against the 5.3.0-SNAPSHOT reactor at commit `ef448827d9fff145b86d0662703871129cd4dfc8`. The JUnit test covers discovery filters, migration of existing channel IDs, state mapping, commands and pairing. Checkstyle, PMD and SpotBugs ran during the build. The Thing and add-on descriptions were also validated against the official XML schemas.

At the time of the build, the snapshot feature catalogue contained conflicting Karaf dependencies. The successful Maven `verify` run used the openHAB 5.3.0.M5 distribution catalogue for the Karaf resolution check. Snapshot dependencies can change between builds.

To repeat the M5 check, use a temporary feature descriptor. Maven must have access to the M5 catalogue and its artefacts. Compilation still uses the reactor dependencies.

```sh
module=bundles/org.openhab.binding.homematicipcloud
descriptor=$(mktemp)
sed 's#mvn:org.openhab.core.features.karaf/org.openhab.core.features.karaf.openhab-core/${ohc.version}/xml/features#mvn:org.openhab.distro/distro/5.3.0.M5/xml/features#' \
    "$module/src/main/feature/feature.xml" > "$descriptor"
./mvnw -pl :org.openhab.binding.homematicipcloud -Dfeature.directory="$descriptor" verify
```

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
