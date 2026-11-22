# Entwicklung und Verifikation

Das Repository enthält ein Maven-Modul für den openHAB-Add-ons-Reaktor. Der übergeordnete POM, die Compiler-Konfiguration, die Formatierung und die statischen Prüfungen stammen aus dem offiziellen Add-ons-Projekt. Die Release-JAR liegt zusätzlich in `dist/`. Ihr Dateiname folgt der Maven-Version des Reaktors. Der Release-Tag des Community-Projekts und die Prüfsumme identifizieren die konkrete Datei.

## Build im openHAB-Reaktor

Voraussetzungen sind Git, JDK 21 und beim ersten Build Internetzugang zum Laden der Maven- und npm-Abhängigkeiten. Die folgenden Schritte gehen von zwei benachbarten Checkouts aus.

```sh
git clone https://github.com/SirDocBrown/openhab-binding-homematicipcloud.git
git clone https://github.com/openhab/openhab-addons.git
cd openhab-addons
git checkout ef448827d9fff145b86d0662703871129cd4dfc8
module=bundles/org.openhab.binding.homematicipcloud
mkdir -p "$module"
cp -R ../openhab-binding-homematicipcloud/src "$module/"
cp ../openhab-binding-homematicipcloud/pom.xml ../openhab-binding-homematicipcloud/README.md "$module/"
```

Trage in `bundles/pom.xml` unter `<modules>` neben dem vorhandenen Homematic-Binding das zusätzliche Modul ein.

```xml
<module>org.openhab.binding.homematicipcloud</module>
```

Starte im Wurzelverzeichnis von `openhab-addons` den Build.

```sh
./mvnw -pl :org.openhab.binding.homematicipcloud -am verify
```

Die JAR liegt anschließend in `bundles/org.openhab.binding.homematicipcloud/target/`. Die Tests verwenden synthetische Cloud-Antworten. Für die Tests werden keine Zugangsdaten benötigt.

## Prüfstand des veröffentlichten Builds

- Kompiliert mit Java 21 gegen den Add-ons-Reaktor 5.3.0-SNAPSHOT am oben genannten Commit.
- Der JUnit-Test prüft Discovery-Filter, Migration bestehender Channel-IDs, Zustandsabbildung, Befehle und Kopplung. Ein isolierter openHAB-5.3.0.M5-Feature-Katalog diente zur abschließenden Karaf-Auflösungsprüfung mit Maven `verify`.
- Checkstyle, PMD und SpotBugs wurden ausgeführt. Die Thing- und Add-on-Beschreibungen wurden zusätzlich lokal mit den offiziellen XML-Schemas validiert.
- Der damalige ungeänderte Snapshot-Feature-Katalog enthielt widersprüchliche Karaf-Abhängigkeiten. Ein erneuter Snapshot-Build kann andere Abhängigkeiten auflösen. Für die M5-Prüfung wurde im Feature-Deskriptor nur die Repository-Referenz auf `mvn:org.openhab.distro/distro/5.3.0.M5/xml/features` umgestellt. Die Quellen der JAR waren identisch.

Die folgende Variante wiederholt die M5-Prüfung mit einem separaten Feature-Deskriptor. Sie setzt einen für Maven erreichbaren M5-Distributionskatalog und dessen Artefakte voraus. Der Maven-Compiler verwendet weiterhin die Versionen des Reaktors.

```sh
module=bundles/org.openhab.binding.homematicipcloud
descriptor=$(mktemp)
sed 's#mvn:org.openhab.core.features.karaf/org.openhab.core.features.karaf.openhab-core/${ohc.version}/xml/features#mvn:org.openhab.distro/distro/5.3.0.M5/xml/features#' \
    "$module/src/main/feature/feature.xml" > "$descriptor"
./mvnw -pl :org.openhab.binding.homematicipcloud -Dfeature.directory="$descriptor" verify
```

## Aufbau des Bindings

| Klasse | Aufgabe |
| --- | --- |
| `Factory` | Erzeugt Bridge- und Gerätehandler. |
| `Cloud` | Kapselt HTTPS-Anfragen, Kopplung, Authentifizierung und WebSocket-Verbindung. |
| `Access` | Verwaltet Verbindung, Wiederverbindung, Gerätebestand und Zustandsaktualisierung. |
| `Discovery` | Meldet Endgeräte und optional Gruppen an die Inbox der jeweiligen Bridge. |
| `Entity` | Erzeugt und aktualisiert die Channels eines Geräts oder einer Gruppe. |
| `Points` | Übersetzt Cloud-Felder in Zustände und unterstützte Befehle. |

Bei Erweiterungen sollte ein synthetisches Beispiel des Funktionskanals sowohl den gelesenen Zustand als auch gegebenenfalls die erzeugte Befehlsanfrage prüfen. Vorhandene Channel-IDs bleiben für bestehende Item-Links relevant.
