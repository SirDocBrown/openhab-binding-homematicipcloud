# Homematic IP Cloud Binding für openHAB

Dieses Community-Binding verbindet openHAB über den Homematic IP Access Point mit der Homematic-IP-Cloud. Geräte erscheinen als Things in openHAB. Ein Fensterkontakt kann beispielsweise eine Heizungsregel auslösen, ein Rollladen lässt sich über ein Rollershutter-Item bedienen. Für diese Verbindung sind ein Access Point mit Internetzugang und die dort angelernten Geräte ausreichend.

**Kompatibilität:** Die Maven-JAR wurde mit Java 21 gebaut und gegen den Karaf-Feature-Katalog von openHAB **5.3.0.M5** geprüft. Kopplung und Fensterzustand wurden in einer früheren Version an einem HmIP-HAP2 mit HmIP-SWDM im Haushalt bestätigt. Das Binding verwendet die inoffizielle Cloud-Schnittstelle. Die Bedienung weiterer Geräte hängt von den Funktionskanälen ab, die die Cloud meldet.

[Release-Downloads](https://github.com/SirDocBrown/openhab-binding-homematicipcloud/releases) · [JAR im Repository](dist/org.openhab.binding.homematicipcloud-5.3.0-SNAPSHOT.jar) · [Build und Tests](docs/DEVELOPMENT.md) · [English guide](docs/README.en.md)

## Installation und erstes Gerät

1. Lade die JAR und auf Wunsch die Datei `SHA256SUMS` herunter. Im Downloadordner prüft `shasum -a 256 -c SHA256SUMS` unter macOS die Datei. Unter Linux lautet der Befehl `sha256sum -c SHA256SUMS`.
2. Kopiere die JAR in den Add-ons-Ordner deiner openHAB-Installation. Bei Docker und QNAP Container Station ist das der Hostordner, der auf `/openhab/addons` gemountet ist. Der Benutzer `openhab` im Container braucht Leserechte auf die Datei und Zugriff auf den Ordner.
3. Öffne **Einstellungen → Things → + → Homematic IP Cloud** und lege einen **Homematic IP Access Point** an. Trage die 24-stellige SGTIN vom Geräteaufkleber ein. Falls eine System-PIN gesetzt ist, trage sie ebenfalls ein. Lass das Token-Feld bei der ersten Kopplung leer.
4. Warte auf die Aufforderung in der Bridge und drücke kurz die Systemtaste am Access Point. Das Kopplungsfenster beträgt zwei Minuten. openHAB speichert das bestätigte Token in der Bridge.
5. Sobald die Bridge **ONLINE** ist, starte unter **Things → + → Homematic IP Cloud** den Scan. Übernimm das gewünschte Gerät aus der Inbox. Öffne am Gerät den Reiter **Channels** und verknüpfe den gewünschten Channel mit einem Item.

Beim HmIP-SWDM meldet `ch1_windowState` den Fensterzustand als `Contact` mit `OPEN` oder `CLOSED`. Die Standardansicht priorisiert Fensterzustand, Batteriewarnung und Nichterreichbarkeit. Diagnosekanäle sind als erweitert gekennzeichnet. Heizungs- und Schaltgruppen erscheinen beim Scan, wenn du an der Bridge **Gruppen suchen** aktivierst.

## Unterstützte Funktionen

| Bereich | Was openHAB lesen oder steuern kann |
| --- | --- |
| Fenster | Offen/geschlossen sowie der detaillierte Cloud-Zustand, sofern vom Gerät gemeldet. Ein gekipptes Fenster zählt als offen. |
| Rollläden und Jalousien | Hoch, runter, Stopp und Zielposition in Prozent. Bei unterstützten Jalousien lässt sich die Lamellenposition separat einstellen. 0 % entspricht offen. |
| Beleuchtung | Ein/Aus, Helligkeit und bei kompatiblen Signallichtern eine vordefinierte Farbpalette. |
| Heizungsgruppen | Solltemperatur, Boost, Automatik/manuell und Auswahl eines vorhandenen Heizprofils. |
| Sensorik und Diagnose | Weitere gemeldete Zahlen, Texte und Schaltzustände, etwa Batterie, Erreichbarkeit oder Messwerte. Generische Messwerte verwenden die Rohskala der Cloud. |

Der genaue Funktionsumfang ergibt sich aus dem Gerät. Das Binding liest skalare Cloud-Felder dynamisch. Schreibbefehle sind für die oben aufgeführten Funktionen implementiert. Der erste Abgleich erfolgt über HTTPS, dann folgen WebSocket-Ereignisse sowie alle fünf Minuten ein vollständiger Abgleich.

## Aktualisieren und Fehler beheben

- **Update einer vorhandenen Installation:** Bewahre die alte JAR außerhalb des Add-ons-Ordners auf und ersetze sie dort durch die neue. Erhalte die Dateirechte. Bridge und Things weiterverwenden. Die bestehenden Channel-IDs, etwa `ch1_windowState`, bleiben beim Wechsel von 0.1.0 erhalten. Starte nach dem Update einen Scan, um alte Inbox-Vorschläge zu aktualisieren.
- **„Error reading bundle content“:** Prüfe, ob der openHAB-Benutzer die Datei tatsächlich lesen kann. Bei einem Container namens `openhab` zeigt `docker exec -u openhab openhab sh -c 'sha256sum /openhab/addons/org.openhab.binding.homematicipcloud-5.3.0-SNAPSHOT.jar'` sowohl Zugriffsprobleme als auch die Prüfsumme. Setze erforderliche Dateirechte oder Freigabe-ACLs am gemounteten Hostordner.
- **Kopplung abgelaufen:** Deaktiviere die Bridge, aktiviere sie erneut und drücke die Taste, sobald die Aufforderung erscheint.
- **Fehlender Channel oder Befehl:** Gib bei einem Fehlerbericht openHAB-Version, Modell, Binding-Version, Channel-ID und beobachtetes Verhalten an. Entferne Token, PIN, Seriennummern und persönliche Gerätekennungen aus Anhängen und Protokollen.

## Lizenz und Protokollreferenzen

Das Projekt steht unter der Eclipse Public License 2.0. Die vorhandenen Protokollimplementierungen bieten Hintergrund zur Kommunikation mit der Homematic-IP-Cloud.

[LICENSE](LICENSE) · [ioBroker.hmip](https://github.com/iobroker-community-adapters/ioBroker.hmip) · [homematicip-rest-api](https://github.com/hahn-th/homematicip-rest-api)
