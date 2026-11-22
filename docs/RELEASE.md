# v0.2.1

Erste für die Veröffentlichung vorbereitete Maven-Ausgabe des Community-Bindings. Zielsystem ist openHAB 5.3.0.M5 mit Java 21. Die Release-JAR trägt die Maven-Kennung 5.3.0-SNAPSHOT.

- Geräteerkennung am Access Point mit optionaler Gruppensuche.
- Fensterzustand, Batteriewarnung und Erreichbarkeit priorisiert. Technische Channels sind als erweitert gekennzeichnet.
- Bestehende Geräte- und Channel-IDs bleiben beim Update erhalten.
- Maven-Regressionstest und Karaf-Prüfung gegen den Feature-Katalog von openHAB 5.3.0.M5 bestanden. Einzelheiten zum Prüfstand stehen in der Entwicklungsdokumentation.

Zur Installation die JAR in den openHAB-Add-ons-Ordner kopieren. Die mitgelieferte Datei SHA256SUMS ermöglicht den Vergleich mit dem geprüften Build.
