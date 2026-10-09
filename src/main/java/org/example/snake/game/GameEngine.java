package org.example.snake.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.stereotype.Component;

/**
 * Das Herzstück des Spiels: verwaltet alle Schlangen und Futter-Objekte,
 * berechnet pro Tick die Bewegung, prüft Kollisionen und erzeugt die
 * Zustands-Snapshots, die an die Browser-Clients gesendet werden.
 *
 * Diese Klasse ist bewusst so geschrieben, dass sie unabhängig von der
 * WebSocket-Schicht funktioniert (Single Responsibility) - sie kennt nur
 * Spiel-Logik, keine Netzwerk-Details.
 */
@Component
public class GameEngine {

    private final Map<String, Snake> snakes = new ConcurrentHashMap<>();
    private final Map<String, Food> foods = new ConcurrentHashMap<>();
    private final AiController aiController = new AiController();
    private final Random random = new Random();
    private final ConcurrentLinkedQueue<DeathEvent> deathEvents = new ConcurrentLinkedQueue<>();

    private static final String[] AI_NAMES = {
            "Viper", "Wurmi", "Sly", "Nibbler", "Blitzschlange", "Rakete", "Pixel",
            "Boa", "Flitzer", "Kobra", "Anaconda", "Shadow", "Turbo", "Mamba", "Zick-Zack",
            "Python", "Ringelnatter", "Giftzahn", "Schleicher", "Wirbel", "Natter",
            "Sidewinder", "Constrictor", "Hornviper", "Schlingel", "Zischling",
            "Nixe", "Draco", "Flinkzunge", "Sepia", "Krake", "Phantom", "Komet"
    };

    /** Ein Skin besteht aus einer Kopf- und einer Schwanzfarbe (für einen Farbverlauf am Körper). */
    public record Skin(String id, String displayName, String primary, String secondary) {
    }

    /** Verfügbare Skins - werden im Menü zur Auswahl angeboten (Client kennt dieselbe Liste). */
    public static final Skin[] SKINS = {
            new Skin("fire", "Feuer", "#ff5555", "#ffb347"),
            new Skin("ocean", "Ozean", "#55aaff", "#55ffe0"),
            new Skin("forest", "Wald", "#55ff88", "#2e8b57"),
            new Skin("galaxy", "Galaxie", "#aa55ff", "#ff55dd"),
            new Skin("gold", "Gold", "#ffdd55", "#ff9955"),
            new Skin("neon", "Neon-Pink", "#ff55dd", "#aa55ff"),
            new Skin("ice", "Eis", "#66ddff", "#ffffff"),
            new Skin("lava", "Lava", "#ff3300", "#330000"),
            new Skin("toxic", "Giftgrün", "#c6ff55", "#55ff88"),
            new Skin("sunset", "Sonnenuntergang", "#ff9955", "#ff5599"),
    };

    private static Skin findSkin(String id) {
        for (Skin s : SKINS) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    private Skin randomSkin() {
        return SKINS[random.nextInt(SKINS.length)];
    }

    public GameEngine() {
        // Welt initial mit Futter befüllen
        while (foods.size() < GameConfig.FOOD_TARGET_COUNT) {
            spawnFood();
        }
        // Karte mit Computer-Schlangen auffüllen
        maintainAiPopulation();
    }

    public Map<String, Snake> getSnakes() {
        return snakes;
    }

    public Map<String, Food> getFoods() {
        return foods;
    }

    /**
     * Fügt eine neue Spieler-Schlange hinzu (Slot 1 oder 2). Ist die Karte voll,
     * wird dafür eine zufällige KI-Schlange entfernt, damit Spieler immer
     * einsteigen können.
     */
    public synchronized String addPlayerSnake(int slot, String name, String skinId) {
        if (snakes.size() >= GameConfig.MAX_SNAKES) {
            snakes.values().stream()
                    .filter(s -> !s.isPlayer())
                    .findAny()
                    .ifPresent(s -> snakes.remove(s.id));
        }
        Vector2 spawn = randomSpawnPosition();
        String id = UUID.randomUUID().toString();
        Skin skin = findSkin(skinId);
        if (skin == null) skin = randomSkin();
        Snake snake = new Snake(id, name == null || name.isBlank() ? ("Spieler " + slot) : name,
                skin.primary(), skin.secondary(), slot, spawn, random.nextDouble() * Math.PI * 2);
        snakes.put(id, snake);
        return id;
    }

    public synchronized void removeSnake(String id) {
        snakes.remove(id);
    }

    public void setControl(String snakeId, int turnInput, boolean boosting) {
        Snake s = snakes.get(snakeId);
        if (s != null) {
            s.turnInput = Math.max(-1, Math.min(1, turnInput));
            s.boosting = boosting;
            s.manualDesiredAngle = null; // Tastatursteuerung deaktiviert den Touch-Winkel-Modus
        }
    }

    /**
     * Steuerung für den Touch-Joystick: Statt eines diskreten turnInput wird
     * direkt die gewünschte Weltrichtung übergeben. Die Auflösung in einen
     * turnInput passiert serverseitig in {@link #tick(double)}, damit der
     * Vergleich aktueller/gewünschter Winkel ohne Netzwerk-Latenz erfolgt
     * (sonst überschwingt die Steuerung auf langsamen Mobilfunkverbindungen,
     * siehe Abschnitt 8.6/4.7 in SPEC.md).
     */
    public void setControlAngle(String snakeId, double desiredAngle, boolean boosting) {
        Snake s = snakes.get(snakeId);
        if (s != null) {
            s.manualDesiredAngle = desiredAngle;
            s.boosting = boosting;
        }
    }

    /** Führt einen Simulationsschritt aus. */
    public synchronized void tick(double dt) {
        // Körper-Cache für KI-Ausweichlogik auf Basis der Positionen VOR
        // dieser Bewegung aktualisieren (entspricht dem bisherigen Verhalten).
        refreshBodyCache();

        // Spawn-Schutz-Timer herunterzählen (siehe Snake.spawnProtectionTimer).
        for (Snake s : snakes.values()) {
            if (s.spawnProtectionTimer > 0) {
                s.spawnProtectionTimer = Math.max(0, s.spawnProtectionTimer - dt);
            }
        }

        // 1. KI-Eingaben berechnen
        for (Snake s : snakes.values()) {
            if (s.alive && !s.isPlayer()) {
                aiController.update(s, this, dt);
            }
        }

        // 1b. Touch-Joystick-Schlangen: gewünschten Winkel (ohne Latenz,
        // direkt hier auf dem Server) in einen turnInput auflösen - siehe
        // Snake.manualDesiredAngle.
        for (Snake s : snakes.values()) {
            if (s.alive && s.manualDesiredAngle != null) {
                s.turnInput = AiController.staticTurnTowards(s.angle, s.manualDesiredAngle);
            }
        }

        // 2. Bewegung
        for (Snake s : snakes.values()) {
            if (s.alive) {
                s.advance(dt);
            }
        }

        // Körper-Cache nach der Bewegung neu berechnen, damit Kollision,
        // Futter-Check und Broadcast die aktuellen Positionen verwenden.
        refreshBodyCache();

        // 3. Kartenrand-Kollision: wer den Rand verlässt, stirbt ebenfalls -
        // und zwar über dieselbe toKill-Liste wie Schlangen-Kollisionen, damit
        // auch hierfür ein DeathEvent erzeugt wird (sonst bleibt der Client
        // beim Erreichen des Randes ohne Game-Over-Anzeige hängen).
        // Frisch gespawnte (noch geschützte) Schlangen können hierdurch nicht sterben.
        List<Snake> toKill = new ArrayList<>();
        for (Snake s : snakes.values()) {
            if (!s.alive || s.isSpawnProtected()) continue;
            Vector2 h = s.head();
            if (h.x < 0 || h.y < 0 || h.x > GameConfig.MAP_WIDTH || h.y > GameConfig.MAP_HEIGHT) {
                toKill.add(s);
            }
        }

        // 4. Schlangen-Kollision: stirbt nur, wessen KOPF eine ANDERE Schlange berührt.
        // Die eigene Selbstberührung tötet bewusst nicht (wie bei Wormate.io),
        // man kann sich also problemlos selbst "einringeln". Schlangen mit
        // aktivem Spawn-Schutz sterben nicht UND zählen auch nicht als
        // Hindernis für andere (sie sind für die Dauer des Schutzes "Geister").
        double collisionDist = GameConfig.SNAKE_RADIUS * 1.7;
        for (Snake s : snakes.values()) {
            if (!s.alive || toKill.contains(s) || s.isSpawnProtected()) continue;
            Vector2 head = s.head();
            boolean dead = false;
            for (Snake other : snakes.values()) {
                if (!other.alive || other == s || other.isSpawnProtected()) continue;
                List<Vector2> body = other.cachedBody;
                for (int i = 1; i < body.size(); i++) {
                    if (head.distanceTo(body.get(i)) < collisionDist) {
                        dead = true;
                        break;
                    }
                }
                if (dead) break;
            }
            if (dead) toKill.add(s);
        }


        // 5. Futter essen (tote/sterbende Schlangen dürfen nicht mehr fressen)
        double eatDist = GameConfig.SNAKE_RADIUS + GameConfig.FOOD_RADIUS;
        for (Snake s : snakes.values()) {
            if (!s.alive || toKill.contains(s)) continue;
            Vector2 head = s.head();
            Food eaten = null;
            for (Food f : foods.values()) {
                if (head.distanceTo(f.position) < eatDist) {
                    eaten = f;
                    break;
                }
            }
            if (eaten != null) {
                foods.remove(eaten.id);
                s.grow(GameConfig.GROWTH_PER_FOOD);
            }
        }

        // 6. Tote verarbeiten: in Futter verwandeln + entfernen
        for (Snake dead : toKill) {
            dead.alive = false;
            scatterFoodFromSnake(dead);
            snakes.remove(dead.id);
            deathEvents.add(new DeathEvent(dead.id, dead.playerSlot, dead.name, dead.length));
        }

        // 7. Welt-Erhaltung: Futter (Unter- UND Obergrenze) & KI-Population auffüllen
        replenishFood();
        maintainAiPopulation();
    }

    /**
     * Hält die Futtermenge zwischen FOOD_TARGET_COUNT (Minimum, damit immer
     * genug zum Fressen da ist) und FOOD_MAX_COUNT (Maximum, damit die Karte
     * durch z. B. viele Tode auf einmal nicht mit Futter "zugemüllt" wird).
     */
    private void replenishFood() {
        while (foods.size() < GameConfig.FOOD_TARGET_COUNT) {
            spawnFood();
        }
        if (foods.size() > GameConfig.FOOD_MAX_COUNT) {
            int excess = foods.size() - GameConfig.FOOD_MAX_COUNT;
            List<String> ids = new ArrayList<>(foods.keySet());
            for (int i = 0; i < excess && i < ids.size(); i++) {
                foods.remove(ids.get(i));
            }
        }
    }

    /** Liefert alle seit dem letzten Aufruf aufgetretenen Todesfälle und leert die Warteschlange. */
    public List<DeathEvent> drainDeathEvents() {
        List<DeathEvent> result = new ArrayList<>();
        DeathEvent e;
        while ((e = deathEvents.poll()) != null) {
            result.add(e);
        }
        return result;
    }

    private void scatterFoodFromSnake(Snake snake) {
        List<Vector2> body = snake.sampledBody(GameConfig.SNAKE_RADIUS * 3);
        // Nicht mehr streuen, als unter der Obergrenze FOOD_MAX_COUNT noch Platz ist -
        // sonst kann eine Welle gleichzeitiger Tode (z. B. viele KI-Schlangen) die
        // Karte dauerhaft mit Futter überfluten.
        int remainingCapacity = Math.max(0, GameConfig.FOOD_MAX_COUNT - foods.size());
        int limit = Math.min(Math.min(body.size(), 40), remainingCapacity);
        for (int i = 0; i < limit; i++) {
            Vector2 p = body.get(i);
            String id = UUID.randomUUID().toString();
            foods.put(id, new Food(id, new Vector2(p.x, p.y), GameConfig.FOOD_RADIUS * 1.3, snake.color));
        }
    }

    private void maintainAiPopulation() {
        while (snakes.size() < GameConfig.MAX_SNAKES) {
            Vector2 spawn = randomSpawnPosition();
            String id = UUID.randomUUID().toString();
            String name = pickUniqueAiName();
            Skin skin = randomSkin();
            Snake snake = new Snake(id, name, skin.primary(), skin.secondary(), null, spawn, random.nextDouble() * Math.PI * 2);
            snakes.put(id, snake);
        }
    }

    /**
     * Wählt einen Namen für eine neue KI-Schlange, der unter den aktuell
     * lebenden KI-Schlangen noch nicht vergeben ist. Der Namenspool
     * (AI_NAMES, 33 Einträge) ist bewusst größer als MAX_SNAKES, damit bei
     * voller Karte niemals zwei KI-Schlangen denselben Namen tragen.
     * (Historischer Bug: Der Name wurde rein zufällig aus AI_NAMES gezogen,
     * ohne auf bereits vergebene Namen zu achten, wodurch häufig mehrere
     * Schlangen denselben Namen trugen. Ein späterer Zwischenstand nutzte
     * nummerierte Varianten wie "Viper II" als Fallback - das wirkte aber
     * unbeabsichtigt wie durchgezählte Spielernamen und wurde durch einen
     * größeren Pool ersetzt.)
     */
    private String pickUniqueAiName() {
        Set<String> used = new HashSet<>();
        for (Snake s : snakes.values()) {
            if (!s.isPlayer()) used.add(s.name);
        }
        List<String> pool = new ArrayList<>(List.of(AI_NAMES));
        Collections.shuffle(pool, random);
        for (String candidate : pool) {
            if (!used.contains(candidate)) return candidate;
        }
        // Praktisch unerreichbar (würde mehr gleichzeitige KI-Schlangen als
        // AI_NAMES.length voraussetzen), aber als Fallback werden zwei
        // Pool-Namen zu einem neuen, weiterhin nicht-nummerierten Namen
        // kombiniert (z. B. "Viper Shadow"), statt durchzuzählen.
        Collections.shuffle(pool, random);
        for (String first : pool) {
            for (String second : pool) {
                if (first.equals(second)) continue;
                String candidate = first + " " + second;
                if (!used.contains(candidate)) return candidate;
            }
        }
        return "Schlange-" + UUID.randomUUID().toString().substring(0, 4);
    }

    /** Berechnet den ausgedünnten Körper jeder lebenden Schlange einmal und speichert ihn zwischen. */
    private void refreshBodyCache() {
        for (Snake s : snakes.values()) {
            if (s.alive) {
                s.cachedBody = s.sampledBody(GameConfig.BODY_SAMPLE_SPACING);
            }
        }
    }

    private void spawnFood() {
        String id = UUID.randomUUID().toString();
        Vector2 pos = pickEvenlyDistributedPosition();
        String color = randomSkin().primary();
        foods.put(id, new Food(id, pos, GameConfig.FOOD_RADIUS, color));
    }

    /**
     * Wählt eine Spawn-Position so, dass neues Futter bevorzugt in Bereichen
     * der Karte entsteht, die aktuell WENIG Futter haben. Dazu wird die Karte
     * gedanklich in ein Gitter aus Zellen (FOOD_GRID_CELL_SIZE) eingeteilt,
     * die Belegung pro Zelle anhand des vorhandenen Futters gezählt, und eine
     * der am dünnsten besetzten Zellen zufällig ausgewählt. Das verhindert,
     * dass sich Futter (z. B. durch Streu-Futter toter Schlangen) dauerhaft
     * an einzelnen Stellen klumpt, während andere Bereiche der Karte leer bleiben.
     */
    private Vector2 pickEvenlyDistributedPosition() {
        double cellSize = GameConfig.FOOD_GRID_CELL_SIZE;
        int cols = Math.max(1, (int) Math.ceil(GameConfig.MAP_WIDTH / cellSize));
        int rows = Math.max(1, (int) Math.ceil(GameConfig.MAP_HEIGHT / cellSize));
        int[][] counts = new int[cols][rows];
        for (Food f : foods.values()) {
            int cx = clamp((int) (f.position.x / cellSize), 0, cols - 1);
            int cy = clamp((int) (f.position.y / cellSize), 0, rows - 1);
            counts[cx][cy]++;
        }

        int minCount = Integer.MAX_VALUE;
        for (int cx = 0; cx < cols; cx++) {
            for (int cy = 0; cy < rows; cy++) {
                if (counts[cx][cy] < minCount) minCount = counts[cx][cy];
            }
        }

        List<int[]> candidates = new ArrayList<>();
        for (int cx = 0; cx < cols; cx++) {
            for (int cy = 0; cy < rows; cy++) {
                if (counts[cx][cy] == minCount) candidates.add(new int[]{cx, cy});
            }
        }
        int[] chosen = candidates.get(random.nextInt(candidates.size()));

        double x = chosen[0] * cellSize + random.nextDouble() * cellSize;
        double y = chosen[1] * cellSize + random.nextDouble() * cellSize;
        x = clamp(x, 0, GameConfig.MAP_WIDTH);
        y = clamp(y, 0, GameConfig.MAP_HEIGHT);
        return new Vector2(x, y);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Wählt eine Spawn-Position, die möglichst weit von allen bereits
     * vorhandenen Schlangen entfernt ist: Es werden mehrere zufällige
     * Positionen gewürfelt (innerhalb eines Randabstands zur Kartenwand)
     * und die erste genommen, die den Mindestabstand SPAWN_MIN_DISTANCE zu
     * jeder anderen Schlange einhält. Wird dieser nach allen Versuchen von
     * keiner Position erreicht (z. B. sehr volle Karte), wird die am
     * weitesten entfernte der probierten Positionen verwendet (bester
     * Kompromiss statt komplett zufällig mitten in einer anderen Schlange
     * zu spawnen). Ergänzt den zusätzlichen Spawn-Schutz (siehe
     * Snake.spawnProtectionTimer) als zweite Verteidigungslinie gegen
     * sofortigen Tod direkt nach dem Spawnen.
     */
    private Vector2 randomSpawnPosition() {
        double margin = 300;
        Vector2 best = null;
        double bestDist = -1;
        for (int attempt = 0; attempt < GameConfig.SPAWN_POSITION_ATTEMPTS; attempt++) {
            double x = margin + random.nextDouble() * (GameConfig.MAP_WIDTH - 2 * margin);
            double y = margin + random.nextDouble() * (GameConfig.MAP_HEIGHT - 2 * margin);
            Vector2 candidate = new Vector2(x, y);
            double minDist = nearestSnakeDistance(candidate);
            if (minDist >= GameConfig.SPAWN_MIN_DISTANCE) {
                return candidate;
            }
            if (minDist > bestDist) {
                bestDist = minDist;
                best = candidate;
            }
        }
        return best != null ? best : new Vector2(GameConfig.MAP_WIDTH / 2.0, GameConfig.MAP_HEIGHT / 2.0);
    }

    /** Kürzester Abstand von point zu irgendeinem Körperpunkt einer lebenden Schlange. */
    private double nearestSnakeDistance(Vector2 point) {
        double min = Double.MAX_VALUE;
        for (Snake s : snakes.values()) {
            if (!s.alive) continue;
            if (s.cachedBody.isEmpty()) {
                double d = point.distanceTo(s.head());
                if (d < min) min = d;
                continue;
            }
            for (Vector2 p : s.cachedBody) {
                double d = point.distanceTo(p);
                if (d < min) min = d;
            }
        }
        return min;
    }

    /** Baut den kompletten Zustand als einfache Map-Struktur (-> JSON) für den Client. */
    public Map<String, Object> buildStateSnapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "state");
        state.put("mapWidth", GameConfig.MAP_WIDTH);
        state.put("mapHeight", GameConfig.MAP_HEIGHT);

        List<Map<String, Object>> snakeList = new ArrayList<>();
        for (Snake s : snakes.values()) {
            if (!s.alive) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("name", s.name);
            m.put("color", s.color);
            m.put("color2", s.secondaryColor);
            m.put("slot", s.playerSlot);
            m.put("length", Math.round(s.length));
            m.put("shielded", s.isSpawnProtected());
            m.put("angle", Math.round(s.angle * 1000.0) / 1000.0);
            List<double[]> segs = new ArrayList<>();
            for (Vector2 p : s.cachedBody) {
                segs.add(new double[]{Math.round(p.x * 10) / 10.0, Math.round(p.y * 10) / 10.0});
            }
            m.put("segments", segs);
            snakeList.add(m);
        }
        state.put("snakes", snakeList);

        List<Map<String, Object>> foodList = new ArrayList<>();
        for (Food f : foods.values()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("x", f.position.x);
            fm.put("y", f.position.y);
            fm.put("r", f.radius);
            fm.put("c", f.color);
            foodList.add(fm);
        }
        state.put("food", foodList);

        List<Map<String, Object>> leaderboard = new ArrayList<>();
        snakes.values().stream()
                .filter(s -> s.alive)
                .sorted((a, b) -> Double.compare(b.length, a.length))
                .limit(GameConfig.LEADERBOARD_SIZE)
                .forEach(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", s.name);
                    m.put("length", Math.round(s.length));
                    m.put("isPlayer", s.isPlayer());
                    leaderboard.add(m);
                });
        state.put("leaderboard", leaderboard);

        return state;
    }

    /** Einfache Datenklasse für ein Todes-Ereignis. */
    public static class DeathEvent {
        public final String snakeId;
        public final Integer playerSlot;
        public final String name;
        public final double finalLength;

        public DeathEvent(String snakeId, Integer playerSlot, String name, double finalLength) {
            this.snakeId = snakeId;
            this.playerSlot = playerSlot;
            this.name = name;
            this.finalLength = finalLength;
        }
    }
}
