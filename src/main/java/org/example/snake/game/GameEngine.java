package org.example.snake.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
            "Boa", "Flitzer", "Kobra", "Anaconda", "Shadow", "Turbo", "Mamba", "Zick-Zack"
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
        }
    }

    /** Führt einen Simulationsschritt aus. */
    public synchronized void tick(double dt) {
        // 1. KI-Eingaben berechnen
        for (Snake s : snakes.values()) {
            if (s.alive && !s.isPlayer()) {
                aiController.update(s, this, dt);
            }
        }

        // 2. Bewegung
        for (Snake s : snakes.values()) {
            if (s.alive) {
                s.advance(dt);
            }
        }

        // 3. Kartenrand-Kollision
        for (Snake s : snakes.values()) {
            if (!s.alive) continue;
            Vector2 h = s.head();
            if (h.x < 0 || h.y < 0 || h.x > GameConfig.MAP_WIDTH || h.y > GameConfig.MAP_HEIGHT) {
                s.alive = false;
            }
        }

        // 4. Schlangen-Kollision: stirbt nur, wessen KOPF eine ANDERE Schlange berührt.
        // Die eigene Selbstberührung tötet bewusst nicht (wie bei Wormate.io),
        // man kann sich also problemlos selbst "einringeln".
        List<Snake> toKill = new ArrayList<>();
        double collisionDist = GameConfig.SNAKE_RADIUS * 1.7;
        for (Snake s : snakes.values()) {
            if (!s.alive) continue;
            Vector2 head = s.head();
            boolean dead = false;
            for (Snake other : snakes.values()) {
                if (!other.alive || other == s) continue;
                List<Vector2> body = other.sampledBody(GameConfig.SNAKE_RADIUS * 1.5);
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

        // 5. Futter essen
        double eatDist = GameConfig.SNAKE_RADIUS + GameConfig.FOOD_RADIUS;
        for (Snake s : snakes.values()) {
            if (!s.alive) continue;
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

        // 7. Welt-Erhaltung: Futter & KI-Population auffüllen
        while (foods.size() < GameConfig.FOOD_TARGET_COUNT) {
            spawnFood();
        }
        maintainAiPopulation();
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
        int limit = Math.min(body.size(), 40);
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
            String name = AI_NAMES[random.nextInt(AI_NAMES.length)];
            Skin skin = randomSkin();
            Snake snake = new Snake(id, name, skin.primary(), skin.secondary(), null, spawn, random.nextDouble() * Math.PI * 2);
            snakes.put(id, snake);
        }
    }

    private void spawnFood() {
        String id = UUID.randomUUID().toString();
        Vector2 pos = new Vector2(random.nextDouble() * GameConfig.MAP_WIDTH, random.nextDouble() * GameConfig.MAP_HEIGHT);
        String color = randomSkin().primary();
        foods.put(id, new Food(id, pos, GameConfig.FOOD_RADIUS, color));
    }

    private Vector2 randomSpawnPosition() {
        double margin = 300;
        double x = margin + random.nextDouble() * (GameConfig.MAP_WIDTH - 2 * margin);
        double y = margin + random.nextDouble() * (GameConfig.MAP_HEIGHT - 2 * margin);
        return new Vector2(x, y);
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
            List<double[]> segs = new ArrayList<>();
            for (Vector2 p : s.sampledBody(GameConfig.SNAKE_RADIUS * 1.4)) {
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
