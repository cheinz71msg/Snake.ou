# Spezifikation: Snake.ou (Multiplayer-Snake-Webspiel)

> **Hinweis für KI-Assistenten / Entwickler:** Diese Datei ist die
> maßgebliche, lebende Spezifikation dieses Projekts. Sie beschreibt Zweck,
> Architektur, Spielregeln, Netzwerkprotokoll und Konfiguration so
> detailliert, dass das gesamte Spiel allein auf Basis dieses Dokuments neu
> entwickelt werden könnte. **Jede funktionale oder architektonisch
> relevante Änderung am Code (neue Features, geänderte Spielregeln,
> geänderte Konfigurationswerte, geändertes Netzwerkprotokoll, geänderte
> Dateistruktur, Deployment-Änderungen) MUSS in derselben Änderung auch
> hier in `SPEC.md` nachgezogen werden.** Reine Refactorings ohne
> Verhaltensänderung müssen nicht dokumentiert werden, es sei denn sie
# ändern die beschriebene Architektur strukturell.

## 1. Projektüberblick

Snake.ou ist ein browserbasiertes Multiplayer-Snake-Spiel im Stil von
[Wormate.io](https://wormate.io/): Spieler steuern eine stetig wachsende
Schlange auf einer großen 2D-Karte, fressen Futter, weichen anderen
Schlangen aus und versuchen, so lange wie möglich zu überleben und die
längste Schlange zu werden. Das Spiel läuft vollständig im Browser
(HTML5 Canvas) und kommuniziert über WebSocket in Echtzeit mit einem
zentralen Java-Server, der die komplette Spiellogik maßgeblich (autoritativ)
berechnet.

### 1.1 Funktionale Anforderungen (Ursprungsvorgaben)

- Webanwendung (kein Download/Installation nötig)
- Hauptmenü mit „Play“-Button zum Start
- Große Karte mit harter Begrenzung an den Rändern (keine „Wrap-Around“-Welt)
- 2D-Ansicht von oben (Top-Down)
- Minimap oben rechts, zeigt die eigene Position auf der Gesamtkarte
- Futter essen lässt die eigene Schlange wachsen (Wormate.io-Mechanik)
- Computergesteuerte (KI) Schlangen als Gegner auf der Karte
- Kollisionsregel: Nur die Schlange, deren **Kopf** eine andere Schlange
  berührt, stirbt. Die berührte andere Schlange bleibt unversehrt. Die
  eigene Selbstberührung ist **nicht** tödlich (man darf sich selbst
  „einringeln“).
- Live-Bestenliste der längsten Schlangen (Spieler + KI), begrenzt auf die
  Top 10
- Maximale Anzahl gleichzeitiger Schlangen ist konfigurierbar (aktuell 20)
- Ansprechende Grafik (keine reine Textdarstellung)
- Sound/Musik im Spiel

> Ursprünglich war zusätzlich ein optionaler lokaler 2. Spieler (zweite
> Tastatursteuerung) vorgesehen. Dieses Feature wurde auf expliziten Wunsch
> **wieder entfernt** - die Architektur (insbesondere das Netzwerkprotokoll
> mit `slot`-Nummern) unterstützt aber weiterhin grundsätzlich mehrere Slots
> pro Verbindung, falls es reaktiviert werden soll.

### 1.2 Nicht-funktionale Anforderungen / Entscheidungen

- **Keine Datenbank**: Der komplette Spielzustand lebt im Arbeitsspeicher
  des Servers (`ConcurrentHashMap`). Beim Neustart des Servers ist der
  Zustand verloren - das ist für dieses Projekt akzeptiert.
- **Ein Prozess, ein Server**: Es ist (noch) keine horizontale Skalierung
  über mehrere Server-Instanzen vorgesehen (kein Redis/Pub-Sub). Alle
  Clients verbinden sich mit derselben Server-Instanz.
- **Autoritativer Server**: Der Server berechnet Bewegung, Kollisionen,
  Wachstum und KI. Der Client sendet nur Eingaben (Drehrichtung, Boost) und
  stellt die vom Server gesendeten Zustände dar. Das verhindert simples
  Cheating durch Client-Manipulation.
- **Sprache der UI/Kommentare**: Deutsch (Zielgruppe: deutschsprachige
  Nutzer; Kommentare im Code sind ebenfalls auf Deutsch, damit der
  jugendliche Projektinhaber den Code nachvollziehen kann).
- **Performance-Ziel**: Soll auch auf einem kostenlosen/CPU-gedrosselten
  Cloud-Server (z. B. Render.com Free Tier) mit 20 gleichzeitigen Schlangen
  flüssig laufen (siehe Abschnitt 6 „Performance“).

## 2. Technologie-Stack

| Schicht | Technologie |
|---|---|
| Backend-Sprache | Java 17 |
| Backend-Framework | Spring Boot 3.3.4 (`spring-boot-starter-web`, `spring-boot-starter-websocket`) |
| Build-Tool | Maven |
| Echtzeit-Kommunikation | WebSocket über **SockJS** (`/ws/game` Endpoint), JSON-Nachrichten via Jackson |
| Frontend | Vanilla JavaScript (kein Framework), HTML5 Canvas 2D, Web Audio API |
| Styling | Reines CSS (keine Präprozessoren/Frameworks) |
| Persistenz | Keine (In-Memory) |
| Deployment | Docker-Image (Multi-Stage-Build), gehostet auf Render.com |

**Warum SockJS statt rohem WebSocket?** SockJS bietet automatisches
Fallback auf HTTP-Streaming/Polling, falls eine Firewall/Security-Software
rohe WebSocket-Verbindungen blockiert. Das war ursprünglich zur Diagnose
eines Verbindungsproblems eingeführt worden (siehe Abschnitt 8.2 für den
tatsächlichen Root Cause), wurde aber als defensive Maßnahme beibehalten.

## 3. Architektur

### 3.1 Backend-Komponenten (`src/main/java/org/example/snake/`)

```
SnakeGameApplication.java         Spring-Boot-Einstiegspunkt (@EnableScheduling)
config/
  WebSocketConfig.java             Registriert den WebSocket-Endpunkt "/ws/game" (+SockJS, allowed origins "*")
game/
  GameConfig.java                  Zentrale Konstanten (siehe Abschnitt 5)
  Vector2.java                     Einfacher 2D-Vektor (x, y) mit distanceTo(), copy()
  Food.java                        Datenklasse: id, position (Vector2), radius, color
  Snake.java                       Kernentität: Bewegung, Pfad-Historie, Wachstum, KI-Zustand
  AiController.java                KI-Steuerlogik für Computer-Schlangen (zustandslos, pro Tick aufgerufen)
  GameEngine.java                  Zentrale Simulation: Tick-Loop, Kollisionen, Futter, Snapshot-Bau, Skins
websocket/
  GameWebSocketHandler.java        Nimmt Verbindungen/Nachrichten entgegen, sendet Broadcasts
  GameLoop.java                    @Scheduled-Treiber: ruft GameEngine.tick() auf und broadcastet Snapshots
```

**Verantwortlichkeiten-Trennung**: `GameEngine` kennt keine
Netzwerk-/WebSocket-Details (Single Responsibility). `GameWebSocketHandler`
übersetzt eingehende JSON-Nachrichten in Aufrufe auf `GameEngine` und
Snapshots zurück in JSON. `GameLoop` ist der einzige Taktgeber
(`@Scheduled`).

### 3.2 Frontend-Dateien (`src/main/resources/static/`)

```
index.html              Markup für Menü-Screen und Spiel-Screen (zwei <section>, via [hidden] umgeschaltet)
css/style.css           Gesamtes Styling (Menü-Glasoptik, Spiel-Panels, Skin-Picker, Bestenliste etc.)
js/
  libs/sockjs.min.js     Vendored SockJS-Client (keine npm-Abhängigkeit zur Laufzeit)
  skins.js               SKIN_LIST: clientseitige Kopie der Skin-Definitionen (siehe 5.3 - muss mit Backend synchron bleiben)
  audio.js               GameAudio-Modul: prozedurale Web-Audio-API-Sounds (kein Audio-Dateien-Download nötig)
  network.js             Network-Modul: SockJS-Verbindung, join/control/leave, Event-Callbacks (onState/onDeath/onJoined)
  render.js              Renderer-Modul: Kamera, Canvas-Zeichnung, Minimap, Bestenliste, HUD, Client-Interpolation
  main.js                Orchestrierung: Menü-Events, Skin-Picker-Aufbau, Tastatur-Input, verbindet Network<->Renderer
```

**Architekturprinzip Frontend**: Jede JS-Datei exponiert ein einziges
globales Modul-Objekt (IIFE-Pattern, z. B. `Network`, `Renderer`,
`GameAudio`) ohne Build-Step/Bundler - bewusst einfach gehalten, damit das
Projekt ohne npm/Webpack auskommt und leicht verständlich bleibt.

### 3.3 Datenfluss (High-Level)

1. Browser lädt `index.html` + CSS/JS statisch vom Spring-Boot-Server.
2. Klick auf „Play“ → `Network.connect()` baut SockJS-Verbindung zu
   `/ws/game` auf.
3. Client sendet `{"type":"join", "slot":1, "name":..., "skin":...}`.
4. Server legt eine neue `Snake`-Instanz an, sendet
   `{"type":"joined", "slot":1, "snakeId":...}` zurück.
5. `GameLoop` läuft serverseitig mit fester Taktrate (`TICK_RATE`,
   s. u.) und ruft `GameEngine.tick(dt)` auf (Bewegung, Kollision, Futter,
   KI, Welterhaltung).
6. In konfigurierbarem Abstand (`BROADCAST_RATE`, s. u.) baut der
   `GameLoop` einen vollständigen Zustands-Snapshot
   (`GameEngine.buildStateSnapshot()`) und sendet ihn per Broadcast an
   **alle** verbundenen Clients (`{"type":"state", ...}`).
7. Der Client speichert die letzten zwei Snapshots und interpoliert
   clientseitig zwischen ihnen für eine flüssige Darstellung unabhängig
   von der tatsächlichen Netzwerk-/Broadcast-Rate (siehe 3.4).
8. Tastatureingaben (Pfeiltasten/WASD) werden sofort als
   `{"type":"control", "slot":1, "turn":-1|0|1, "boost":true|false}`
   an den Server gesendet (kein Client-seitiges Antizipieren der Bewegung
   - bewusst simpel gehalten, auf Kosten von etwas Eingabe-Latenz).
9. Stirbt die eigene Schlange, sendet der Server
   `{"type":"death", "snakeId":..., "slot":..., "name":..., "length":...}`;
   der Client zeigt ein Game-Over-Overlay.

### 3.4 Client-seitige Interpolation (Smoothing)

Da der Server nicht bei jedem Simulationsschritt sendet (siehe
`BROADCAST_RATE` < `TICK_RATE`), würde die Darstellung ruckeln, wenn der
Client einfach nur den letzten Snapshot zeichnen würde. Stattdessen:

- `render.js` hält `prevState` und `currState` (jeweils mit
  Empfangszeitstempel) vor.
- Ein `requestAnimationFrame`-Loop (`drawLoop`) berechnet
  `factor = clamp((now - prevReceivedAt) / (currReceivedAt - prevReceivedAt), 0, 1)`
  und interpoliert linear die Positionen gleicher Schlangen-IDs zwischen
  den beiden Snapshots.
- Segmente, die nur im neueren Snapshot existieren (z. B. durch Wachstum),
  werden unverändert angehängt.
- Dadurch ist die Bildrate der Darstellung von der Server-Sende-Rate
  entkoppelt und bleibt auch bei 15 Hz Broadcast-Rate flüssig (60 FPS
  Canvas-Zeichnung).

## 4. Spielregeln im Detail

### 4.1 Bewegung

- Jede Schlange hat einen `angle` (Bogenmaß) und bewegt sich **automatisch
  und kontinuierlich** in diese Richtung (kein Stillstand möglich, wie bei
  Wormate.io/Slither.io - nicht wie beim klassischen Raster-Snake).
- Eingabe `turnInput` ∈ {-1, 0, 1} dreht den Winkel pro Tick um maximal
  `TURN_RATE_DEG_PER_SEC * dt` Grad.
- `boosting` (Taste „oben“/„W“ gedrückt) erhöht die Geschwindigkeit um den
  Faktor `BOOST_MULTIPLIER`. Es gibt **keinen** Kosten-/Verbrauchs-Mechanismus
  fürs Boosten (z. B. kein Längenverlust) - das ist eine mögliche künftige
  Erweiterung, aber aktuell bewusst einfach gehalten.
- Die Kopfposition wird als Pfad (`Deque<Vector2>`, neuester Punkt zuerst)
  aufgezeichnet. Der sichtbare/kollisionsrelevante Körper besteht aus den
  Pfadpunkten, deren kumulierte Bogenlänge die aktuelle `length` der
  Schlange nicht übersteigt (ältere Punkte werden abgeschnitten,
  `trimPath()`).

### 4.2 Wachstum

- Start-Länge: `START_LENGTH` Welt-Einheiten.
- Berührt der Kopf ein Futter-Objekt (Abstand < `SNAKE_RADIUS +
  FOOD_RADIUS`), wird das Futter entfernt und `length` um
  `GROWTH_PER_FOOD` erhöht.
- Die Karte hält durchgehend mindestens `FOOD_TARGET_COUNT` Futter-Objekte
  vor (nach jedem Tick wird bei Bedarf nachgefüllt).

### 4.3 Kollision

- **Kartenrand**: Verlässt der Kopf das Rechteck `[0, MAP_WIDTH] x [0,
  MAP_HEIGHT]`, stirbt die Schlange sofort.
- **Andere Schlangen**: Eine Schlange stirbt, wenn ihr **Kopf** näher als
  `SNAKE_RADIUS * 1.7` an einem Körperpunkt (ab Index 1, also nicht am
  gegnerischen Kopf selbst beginnend) **einer anderen** Schlange ist.
  Geprüft wird gegen den zwischengespeicherten, ausgedünnten Körper
  (`cachedBody`, Punktabstand `BODY_SAMPLE_SPACING`).
- **Selbstkollision ist explizit deaktiviert**: `other == s` wird in der
  Kollisionsschleife übersprungen. Eine Schlange kann sich beliebig selbst
  kreuzen/einringeln, ohne zu sterben (Wormate.io-Verhalten, nicht
  klassisches Snake-Verhalten).
- Stirbt eine Schlange, werden aus bis zu 40 Punkten ihres (gröberen,
  `SNAKE_RADIUS * 3` Abstand) Körperpfads neue Futter-Objekte erzeugt
  (`scatterFoodFromSnake`) - andere Schlangen können die „Überreste“
  fressen.

### 4.4 KI-Schlangen (Computer-Gegner)

Eine einzige, zustandslose `AiController`-Instanz wird für **alle**
KI-Schlangen verwendet; aller Zustand (Wander-Timer, Wander-Richtung,
„befindet sich gerade in Wand-Ausweich-Modus“) liegt **pro Schlange** auf
dem jeweiligen `Snake`-Objekt (`aiWanderTimer`, `aiWanderTurn`,
`aiAvoidingWall`), **nicht** im Controller selbst. (Historischer Bug:
Zustand lag ursprünglich im Controller und wurde von allen Schlangen
gemeinsam genutzt, was zu synchronisiertem Fehlverhalten führte - siehe
Abschnitt 8.3.)

Die KI entscheidet pro Tick in dieser Priorität (erste zutreffende Regel
gewinnt, siehe `AiController.update()`):

1. **Kartenrand-Vermeidung** (mit Hysterese): Ist der Kopf näher als 220
   Welt-Einheiten am Rand, wird in Richtung Kartenmitte gelenkt und
   `boosting` deaktiviert. Einmal ausgelöst, bleibt der „Ausweichmodus“
   aktiv, bis der Abstand zum Rand wieder > 420 Einheiten ist (verhindert
   Flackern/Kreisen exakt an der Auslöseschwelle).
2. **Andere Schlangen direkt voraus**: Ein Punkt 130 Einheiten vor dem
   Kopf wird gegen die (zwischengespeicherten) Körper **anderer**
   Schlangen geprüft (Gefahrenradius `SNAKE_RADIUS * 3`). Bei Gefahr wird
   geprüft, ob links oder rechts (±45°) mehr Abstand zum nächsten
   fremden Körper besteht, und in die freiere Richtung gelenkt. **Der
   eigene Körper wird hierbei explizit ignoriert** (seit Deaktivierung der
   Selbstkollision ist er ungefährlich - andernfalls würde sich die KI
   selbst „einkreisen“ und endlos im Kreis drehen, siehe Abschnitt 8.3).
3. **Futter-Suche**: Nächstgelegenes Futter im Umkreis von 900 Einheiten
   wird direkt angesteuert.
4. **Wandern**: Ist nichts davon zutreffend, wird alle 0.8-2.0 Sekunden neu
   gewürfelt: 55 % Wahrscheinlichkeit geradeaus, je 22.5 % leicht links/
   rechts. Dadurch bewegt sich die KI überwiegend geradlinig mit
   gelegentlichen sanften Kursänderungen statt in engen Kreisen zu rotieren.

### 4.5 Bestenliste

- Bei jedem Snapshot werden alle lebenden Schlangen absteigend nach
  `length` sortiert, die Top `LEADERBOARD_SIZE` (10) werden mit `name`,
  gerundeter `length` und `isPlayer`-Flag übertragen.
- Client zeigt Rang 1-3 mit Medaillen-Icons (🥇🥈🥉), Rang 4+ mit
  Nummer. Einträge, die zur eigenen Schlange gehören, werden farblich
  hervorgehoben (grün).

### 4.6 Skins

- 10 serverseitig definierte Skins (`GameEngine.SKINS`), je bestehend aus
  `id`, `displayName`, `primary`-Farbe (Kopf) und `secondary`-Farbe
  (Schwanz/Verlauf-Ende): fire, ocean, forest, galaxy, gold, neon, ice,
  lava, toxic, sunset (siehe Code für exakte Hex-Werte).
- Der Client hält in `skins.js` eine **manuell synchron zu haltende**
  Kopie derselben Liste (`SKIN_LIST`) für die Menü-Darstellung. **Es gibt
  keine automatische Synchronisation** - wird ein Skin im Backend
  geändert/hinzugefügt, muss `skins.js` manuell nachgezogen werden (und
  umgekehrt).
- Beim Menü-Klick auf einen Skin wird dessen `id` im `join`-Request als
  `skin`-Feld mitgeschickt. Der Server sucht die `id` in `SKINS`
  (`findSkin`); ist sie unbekannt/null, wird ein zufälliger Skin
  zugewiesen (`randomSkin()`) - das verhindert beliebige Farb-Injection
  durch manipulierte Clients, da nur IDs aus einer festen Whitelist
  akzeptiert werden.
- KI-Schlangen und Futter-Objekte erhalten ebenfalls zufällige Skin-Farben.
- Darstellung: Körper wird vom Kopf (`color`) zum Schwanz (`color2`)
  farblich interpoliert (`lerpColor` in `render.js`, linear über den
  RGB-Raum, Faktor = Position im Körper von 0 bei Kopf bis 1 beim
  Schwanzende).

## 5. Konfiguration (`GameConfig.java`)

Alle Werte sind zentrale `public static final` Konstanten in
**`src/main/java/org/example/snake/game/GameConfig.java`**. Bei
Änderungswünschen ("Karte größer", "mehr Schlangen", "schnellere
Bewegung") ist i. d. R. **nur diese Datei** anzupassen.

| Konstante | Wert | Bedeutung |
|---|---|---|
| `MAP_WIDTH` / `MAP_HEIGHT` | 4000 / 4000 | Größe der Spielwelt in Welt-Einheiten (nicht Pixel) |
| `MAX_SNAKES` | 20 | Maximale gleichzeitige Schlangen (Spieler + KI) |
| `TICK_RATE` | 30 | Physik-Simulationsschritte pro Sekunde |
| `TICK_INTERVAL_SECONDS` | 1/TICK_RATE | Abgeleitet, `dt` pro Tick |
| `FOOD_TARGET_COUNT` | 350 | Mindestanzahl Futter-Objekte auf der Karte |
| `BASE_SPEED` | 95.0 | Grundgeschwindigkeit (Welt-Einheiten/Sekunde) |
| `BOOST_MULTIPLIER` | 1.8 | Geschwindigkeitsfaktor beim Boosten |
| `TURN_RATE_DEG_PER_SEC` | 220.0 | Maximale Drehgeschwindigkeit |
| `START_LENGTH` | 90.0 | Startlänge einer neuen Schlange |
| `GROWTH_PER_FOOD` | 14.0 | Längenzuwachs pro gefressenem Futter |
| `SNAKE_RADIUS` | 9.0 | Kollisions-/Zeichenradius eines Körpersegments |
| `FOOD_RADIUS` | 7.0 | Radius eines Futter-Objekts |
| `LEADERBOARD_SIZE` | 10 | Anzahl Einträge in der Bestenliste |
| `BODY_SAMPLE_SPACING` | `SNAKE_RADIUS * 1.5` | Punktabstand im ausgedünnten Körper-Cache (Kollision/KI/Rendering) |
| `BROADCAST_RATE` | 15 | Zustands-Snapshots pro Sekunde an die Clients (≤ TICK_RATE) |
| `SELF_COLLISION_SKIP_SEGMENTS` | 8 | *(derzeit ungenutzt, historisch von der inzwischen deaktivierten Selbstkollisionsprüfung)* |

**Hinweis für künftige Änderungen:** Wird `TICK_RATE` oder
`BROADCAST_RATE` geändert, passt sich `GameLoop.BROADCAST_EVERY_N_TICKS`
automatisch an (`TICK_RATE / BROADCAST_RATE`, mind. 1). Die
Client-Interpolation (Abschnitt 3.4) misst die tatsächliche Zeit zwischen
Snapshots dynamisch und muss bei Raten-Änderungen **nicht** angepasst
werden.

## 6. Performance-Überlegungen

Das Spiel muss mit bis zu `MAX_SNAKES` (20) gleichzeitigen, potenziell
sehr langen Schlangen auch auf schwacher Hardware (z. B. Render.com Free
Tier) flüssig laufen. Umgesetzte Maßnahmen:

1. **Körper-Cache pro Tick** (`Snake.cachedBody`): Der ausgedünnte
   Schlangenkörper (`sampledBody()`) wird in `GameEngine.tick()` über
   `refreshBodyCache()` **genau zweimal pro Tick** (vor und nach der
   Bewegung) berechnet und dann von Kollisionsprüfung, KI-Ausweichlogik
   und dem Zustands-Snapshot-Bau **wiederverwendet**, statt bei jeder
   einzelnen Prüfung neu aus dem rohen Pfad abgetastet zu werden. Das war
   vor der Optimierung der dominante CPU-Kostenfaktor bei vielen/langen
   Schlangen (quadratisches Verhalten: jede Schlange gegen jede andere,
   mehrfach pro Tick).
2. **Entkoppelte Broadcast-Rate**: Physik läuft weiterhin mit voller
   `TICK_RATE` (30 Hz) für reaktionsschnelle Steuerung/Kollisionen, aber
   der Zustand wird nur mit `BROADCAST_RATE` (15 Hz) über das Netzwerk
   verschickt - halbiert die Serialisierungs- und Bandbreitenlast ohne
   spürbaren Qualitätsverlust (dank Client-Interpolation, Abschnitt 3.4).
3. **Synchronisierte WebSocket-Sends**: Tomcat erlaubt pro Session nur
   einen gleichzeitigen Schreibvorgang. `GameWebSocketHandler.sendRaw()`
   synchronisiert auf das `WebSocketSession`-Objekt, da sowohl der
   Game-Loop-Thread (Broadcast) als auch der Nachrichtenverarbeitungs-Thread
   (z. B. „joined“-Antwort) gleichzeitig senden könnten
   (`IllegalStateException: TEXT_PARTIAL_WRITING` ohne diesen Fix).
4. **Server-Region**: `render.yaml` setzt `region: frankfurt`, um die
   Netzwerklatenz für (vermutlich primär europäische) Nutzer zu
   minimieren.

Mögliche künftige Optimierungen (nicht umgesetzt, falls weitere
Performance-Probleme auftreten): räumliche Partitionierung (Grid/Quadtree)
statt paarweiser O(n²)-Kollisionsprüfung aller Schlangen gegeneinander;
Reduktion von `FOOD_TARGET_COUNT`; Delta-Kompression der Snapshots statt
volle Zustandsübertragung.

## 7. Netzwerkprotokoll (WebSocket/SockJS, JSON)

Endpoint: `/ws/game` (SockJS, `setAllowedOriginPatterns("*")`).

### 7.1 Client → Server

| `type` | Felder | Bedeutung |
|---|---|---|
| `join` | `slot` (int, aktuell immer 1), `name` (string, optional), `skin` (string, Skin-ID, optional) | Tritt dem Spiel bei, erzeugt eine neue Schlange |
| `control` | `slot` (int), `turn` (-1\|0\|1), `boost` (bool) | Setzt die aktuelle Steuereingabe für die Schlange dieses Slots |
| `leave` | `slot` (int) | Verlässt das Spiel, entfernt die Schlange |

### 7.2 Server → Client

| `type` | Felder | Bedeutung |
|---|---|---|
| `joined` | `slot`, `snakeId` | Bestätigung des Beitritts, liefert die serverseitige Schlangen-ID |
| `state` | `mapWidth`, `mapHeight`, `snakes[]`, `food[]`, `leaderboard[]` | Vollständiger Zustands-Snapshot (Broadcast an alle) |
| `death` | `snakeId`, `slot` (-1 bei KI), `name`, `length` | Eine Schlange ist gestorben (Broadcast an alle) |

**`state.snakes[]`** Einträge: `id`, `name`, `color` (Kopf-Hex), `color2`
(Schwanz-Hex), `slot` (Integer oder `null` bei KI), `length` (gerundet),
`segments` (Array aus `[x, y]`-Paaren, ausgedünnter Körper, neuester/
Kopf-Punkt zuerst, Koordinaten auf eine Nachkommastelle gerundet).

**`state.food[]`** Einträge: `x`, `y`, `r` (Radius), `c` (Farbe, Hex).

**`state.leaderboard[]`** Einträge: `name`, `length` (gerundet),
`isPlayer` (bool).

Alle Nachrichten sind einfache flache JSON-Objekte (Jackson
`ObjectMapper`, keine Schemaversionierung). Unbekannte `type`-Werte werden
vom Server ignoriert (`default`-Zweig im `switch`).

## 8. Bekannte Fallstricke / Lessons Learned

Dieser Abschnitt dokumentiert nicht-offensichtliche Bugs, die bereits
gefunden und behoben wurden, damit sie nicht erneut eingeführt werden.

### 8.1 WebSocket-Concurrency

Tomcats `WsRemoteEndpointImplBase` wirft eine `IllegalStateException`,
wenn zwei Threads gleichzeitig `session.sendMessage()` auf derselben
Session aufrufen. Da sowohl der periodische Game-Loop-Broadcast als auch
die direkte Antwort auf eine Client-Nachricht (z. B. `joined`) senden,
**muss** jeder Sendevorgang pro Session synchronisiert werden (siehe
`GameWebSocketHandler.sendRaw()`).

### 8.2 `[hidden]`-Attribut wird von CSS-Selektoren überschrieben

Der Menü→Spiel-Übergang nutzt `element.hidden = true/false`. Das
funktioniert nur, solange keine Autoren-CSS-Regel mit gleicher oder
höherer Spezifität das UA-Default `[hidden] { display: none }`
überschreibt. Eine ID-Selektor-Regel wie `#menu { display: flex; }`
gewinnt gegen `[hidden]` und hebelt das Verstecken aus - das Spiel lief
dann unsichtbar im Hintergrund weiter (WebSocket-Daten kamen korrekt an),
während optisch nur das Menü sichtbar blieb. **Fix**: globale Regel
`[hidden] { display: none !important; }` in `style.css`, die obligatorisch
beibehalten werden muss, wann immer `.hidden`/`element.hidden` zum
Ein-/Ausblenden von Screens verwendet wird.

### 8.3 Geteilter KI-Zustand + Selbstausweichen führte zu Kreisen

Zwei kombinierte Bugs ließen KI-Schlangen (besonders lange) auf der Stelle
rotieren statt sich normal zu bewegen:

1. `AiController` war als **eine einzige Instanz für alle KI-Schlangen**
   angelegt, mit Zustand (`wanderTimer`, `wanderTurn`) als
   Instanzvariablen des Controllers statt pro Schlange. Dadurch
   beeinflussten sich alle KI-Schlangen gegenseitig beim „Wandern“.
2. Die Ausweich-vor-anderen-Schlangen-Logik prüfte ursprünglich **auch den
   eigenen Körper** (mit einer kleinen Anzahl übersprungener „Nacken“-
   Segmente). Bei langen, selbst-gekreuzten Schlangen (Selbstkollision ist
   ja bewusst deaktiviert, s. 4.3) erkannte die KI ihren eigenen,
   mitbewegten Schwanz ständig als Gefahr direkt voraus und drehte
   endlos in eine Richtung.

**Fix**: KI-Zustand liegt jetzt pro Schlange auf dem `Snake`-Objekt selbst
(`aiWanderTimer`, `aiWanderTurn`, `aiAvoidingWall`); die
Ausweichlogik überspringt den eigenen Körper vollständig
(`other == snake` → `continue`). Zusätzlich wurde eine Hysterese bei der
Kartenrand-Vermeidung eingeführt (Auslöseschwelle 220, Deaktivierungs-
schwelle 420 Einheiten), um Flackern/Kreisen exakt an der Randzonen-Grenze
zu verhindern.

### 8.4 Render.com: WebSocket-fähiges Hosting nötig

Statische Hoster (GitHub Pages, itch.io, Netlify-Static) funktionieren
**nicht**, da dieses Spiel einen dauerhaft laufenden Server-Prozess mit
WebSocket-Unterstützung benötigt. Es muss ein Hosting gewählt werden, das
langlebige Server-Prozesse erlaubt (Render.com, Railway.app, Fly.io,
eigener VPS). Aktuell gewählt: **Render.com**, Free-Tier, via Docker
(`Dockerfile`) und `render.yaml`-Blueprint für automatisches CI/CD bei
jedem `git push` auf `main`. Bekannter Nachteil des Free-Tiers: Der
Server schläft nach ~15 Minuten Inaktivität ein und braucht beim nächsten
Aufruf 30-60 Sekunden zum Aufwachen (Cold Start).

## 9. Deployment

- **Repository**: GitHub, `cheinz71msg/Snake.ou`, Branch `main`.
- **Dockerfile**: Multi-Stage-Build - Stage 1 baut mit
  `maven:3.9-eclipse-temurin-17` das Jar (`mvn clean package`), Stage 2
  kopiert nur das fertige Jar in ein schlankes `eclipse-temurin:17-jre-alpine`
  Image.
- **Port-Konfiguration**: `application.properties` setzt
  `server.port=${PORT:8080}` - Render (und die meisten Cloud-Hoster) geben
  den Port über die Umgebungsvariable `PORT` vor; lokal ohne diese Variable
  wird weiterhin Port 8080 verwendet.
- **`render.yaml`**: Render-„Blueprint“, das beim Verbinden des Repos
  automatisch erkannt wird (`runtime: docker`, `plan: free`,
  `region: frankfurt`, `autoDeploy: true`). Jeder Push auf `main` löst
  automatisch einen Rebuild + Redeploy aus.
- **Lokales Starten** (ohne Docker):
  ```
  mvn clean package
  java -jar target/Snake.ou-1.0-SNAPSHOT.jar
  ```
  Danach `http://localhost:8080` im Browser öffnen.

## 10. Erweiterungsideen (nicht umgesetzt, nur zur Orientierung)

Diese Punkte sind **keine** aktuellen Anforderungen, sondern Ideen, die bei
Bedarf mit dem Projektinhaber abgestimmt werden sollten, bevor sie
umgesetzt werden:

- Zweiter lokaler Spieler (Architektur unterstützt grundsätzlich mehrere
  Slots, UI/Logik wurde aber bewusst entfernt, s. 1.1)
- Persistente Bestenliste über Server-Neustarts hinweg (würde eine
  Datenbank oder zumindest eine Datei-Persistenz erfordern)
- Räumliche Partitionierung für bessere Skalierung bei sehr vielen
  Schlangen/sehr großer Karte
- Boost mit Längenkosten (wie bei Slither.io, wo Boosten Masse/Länge
  kostet)
- Automatisierte Tests (aktuell gibt es keine Unit-/Integrationstests;
  Verifikation erfolgte bisher manuell über Node.js/`ws`-Testskripte
  während der Entwicklung, die nicht Teil des Repositories sind)

---
*Diese Spezifikation wurde aus dem iterativen Entwicklungsverlauf dieses
Projekts erstellt und muss bei jeder funktional relevanten Änderung aktuell
gehalten werden (siehe Hinweis am Dateianfang).*
