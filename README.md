# Snake.ou
Browserbasiertes Multiplayer-Snake-Spiel im Stil von Wormate.io.

> 📋 **Vollständige Spezifikation**: Siehe [`SPEC.md`](SPEC.md) für Architektur,
> Spielregeln, Netzwerkprotokoll, Konfiguration und bekannte Fallstricke.
> **Wichtig**: Bei jeder funktional relevanten Codeänderung muss `SPEC.md`
> im selben Zug mit aktualisiert werden - sie ist die maßgebliche,
> lebende Dokumentation dieses Projekts.

## Technik
- **Backend**: Java 17, Spring Boot, WebSocket (Spiel-Loop mit 30 Ticks/Sekunde)
- **Frontend**: Vanilla JavaScript, HTML5 Canvas, Web Audio API
- Keine Datenbank nötig, alles läuft im Arbeitsspeicher des Servers

## Features
- Hauptmenü mit Skin-Auswahl
- Große Karte mit Begrenzung, 2D-Ansicht von oben, Minimap
- Obst essen zum Wachsen, KI-gesteuerte Computer-Schlangen (max. 20 gleichzeitig)
- Kopf-Kollision = Tod (eigener Körper ist ungefährlich)
- Live-Bestenliste (Top 10)
- Sound-Effekte und Hintergrundmusik

## Lokal starten
```
mvn clean package
java -jar target/Snake.ou-1.0-SNAPSHOT.jar
```
Danach im Browser `http://localhost:8080` öffnen.

## Deployment (Render.com)
Das Projekt enthält ein `Dockerfile` und eine `render.yaml`. Bei Render.com
als "New + Blueprint" verbinden -> Render erkennt die Konfiguration
automatisch, baut das Docker-Image und deployed bei jedem `git push`
auf den `main`-Branch neu.
