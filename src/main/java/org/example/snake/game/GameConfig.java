package org.example.snake.game;

/**
 * Zentrale Spielkonstanten. Hier lassen sich alle wichtigen Stellschrauben
 * des Spiels an einer Stelle anpassen.
 */
public final class GameConfig {

    private GameConfig() {
    }

    /** Breite der gesamten Spielwelt in "Welt-Einheiten" (nicht Pixel!). */
    public static final double MAP_WIDTH = 4000;

    /** Höhe der gesamten Spielwelt in "Welt-Einheiten". */
    public static final double MAP_HEIGHT = 4000;

    /** Maximale Anzahl gleichzeitig lebender Schlangen (Spieler + Computer). */
    public static final int MAX_SNAKES = 20;

    /** Wie oft pro Sekunde die Spiel-Physik berechnet wird (Server-Tickrate). */
    public static final int TICK_RATE = 30;

    public static final double TICK_INTERVAL_SECONDS = 1.0 / TICK_RATE;

    /** Minimale Anzahl der Futter-Objekte, die laufend nachgefüllt wird (siehe auch FOOD_MAX_COUNT). */
    public static final int FOOD_TARGET_COUNT = 350;

    /** Grundgeschwindigkeit einer Schlange in Welt-Einheiten pro Sekunde. */
    public static final double BASE_SPEED = 95.0;

    /** Geschwindigkeits-Multiplikator beim Boosten (Spieler hält Taste "oben"/"w"). */
    public static final double BOOST_MULTIPLIER = 1.8;

    /** Maximale Drehgeschwindigkeit in Grad pro Sekunde. */
    public static final double TURN_RATE_DEG_PER_SEC = 220.0;

    /** Start-Länge einer neuen Schlange in Welt-Einheiten. */
    public static final double START_LENGTH = 90.0;

    /** Längenzuwachs pro gegessenem Futter-Stück. */
    public static final double GROWTH_PER_FOOD = 14.0;

    /** Radius eines Schlangen-Körpersegments (für Kollision & Zeichnung). */
    public static final double SNAKE_RADIUS = 9.0;

    /** Radius eines Futter-Objekts. */
    public static final double FOOD_RADIUS = 7.0;

    /**
     * Harte Obergrenze für die Gesamtzahl an Futter-Objekten auf der Karte.
     * Verhindert, dass nach vielen gleichzeitigen Todesfällen (jeder tote
     * Snake streut Futter entlang seines Körpers) die Karte dauerhaft
     * "zugemüllt" wird und normales, gleichmäßig verteiltes Nachspawnen
     * ausbleibt, weil die Gesamtmenge schon über dem Minimum liegt. Muss
     * größer oder gleich FOOD_TARGET_COUNT sein.
     */
    public static final int FOOD_MAX_COUNT = 450;

    /**
     * Kantenlänge (Welt-Einheiten) der Gitterzellen, die für die
     * gleichmäßige Verteilung neu gespawnten Futters verwendet werden
     * (siehe GameEngine.pickEvenlyDistributedPosition). Kleinere Werte
     * ergeben eine feinere, aber etwas teurere Verteilung.
     */
    public static final double FOOD_GRID_CELL_SIZE = 400.0;

    /** Anzahl Einträge in der Bestenliste. */
    public static final int LEADERBOARD_SIZE = 10;

    /**
     * Abstand (in Welt-Einheiten) zwischen den Punkten des ausgedünnten
     * Körpers, der für Kollision, KI-Ausweichlogik und Darstellung verwendet
     * wird. Ein einziger gemeinsamer Wert ermöglicht es, den Körper pro Tick
     * nur einmal zu berechnen und das Ergebnis mehrfach wiederzuverwenden.
     */
    public static final double BODY_SAMPLE_SPACING = SNAKE_RADIUS * 1.5;

    /** Wie viele Snapshots pro Sekunde an die Clients gesendet werden (<= TICK_RATE). */
    public static final int BROADCAST_RATE = 15;

    /**
     * Wie viele "Nacken"-Punkte ab dem Kopf von der Selbstkollisionsprüfung
     * ausgenommen werden, damit die Schlange sich nicht sofort selbst "beißt".
     */
    public static final int SELF_COLLISION_SKIP_SEGMENTS = 8;

    // ---------------------------------------------------------------
    // KI-Verhalten (Futter-Suche & Ausweichen vor anderen Schlangen)
    // ---------------------------------------------------------------

    /**
     * Wie viele Sekunden eine KI ununterbrochen auf dasselbe Futter
     * zusteuern darf, OHNE dass sich der Abstand spürbar verringert, bevor
     * angenommen wird, dass sie "feststeckt" (z. B. weil ihr Kurvenradius
     * bei der aktuellen Geschwindigkeit zu groß ist und sie dauerhaft um
     * das Futter herumkreist). Danach wird das Futter kurzzeitig gesperrt
     * und die KI bricht bewusst aus der Kreisbewegung aus.
     */
    public static final double AI_FOOD_STUCK_SECONDS = 1.8;

    /** Wie lange (Sekunden) eine KI nach erkanntem Kreisen bewusst in eine feste Richtung ausbricht. */
    public static final double AI_BREAK_FREE_SECONDS = 1.0;

    /** Wie lange (Sekunden) ein als "Kreis-Falle" erkanntes Futter danach von der KI ignoriert wird. */
    public static final double AI_FOOD_BLACKLIST_SECONDS = 4.0;

    /** Mindest-Vorschauabstand (Welt-Einheiten) für die Ausweichprüfung vor anderen Schlangen. */
    public static final double AI_AVOID_LOOKAHEAD_MIN = 160.0;

    /**
     * Reaktionszeit (Sekunden), die in den Vorschauabstand einberechnet
     * wird: lookAheadDist = max(AI_AVOID_LOOKAHEAD_MIN, aktuelleGeschwindigkeit * AI_AVOID_LOOKAHEAD_SECONDS).
     * Dadurch schauen schnelle/boostende Schlangen weiter voraus als langsame.
     */
    public static final double AI_AVOID_LOOKAHEAD_SECONDS = 1.1;

    /** Gefahrenradius (Welt-Einheiten) um einen fremden Körperpunkt, innerhalb dessen die KI ausweicht. */
    public static final double AI_AVOID_DANGER_RADIUS = SNAKE_RADIUS * 3.2;

    // ---------------------------------------------------------------
    // Spawn-Sicherheit (verhindert sofortigen Tod direkt nach dem Spawnen)
    // ---------------------------------------------------------------

    /**
     * Mindestabstand (Welt-Einheiten), den eine neu gewählte Spawn-Position
     * von allen bereits vorhandenen Schlangenkörpern haben soll. Wird beim
     * Spawnen mehrfach eine zufällige Position gewürfelt und die am
     * weitesten entfernte genommen, falls keine Position diesen
     * Mindestabstand erreicht (z. B. bei sehr voller Karte).
     */
    public static final double SPAWN_MIN_DISTANCE = 400.0;

    /** Wie viele zufällige Positionen beim Spawnen maximal probiert werden. */
    public static final int SPAWN_POSITION_ATTEMPTS = 25;

    /**
     * Wie viele Sekunden eine frisch gespawnte Schlange unverwundbar ist
     * (kann weder sterben noch von anderen Schlangen "gerammt" werden -
     * sie ist für diese Zeit quasi ein Geist). Verhindert, dass man direkt
     * nach dem Spawnen durch eine zufällig in der Nähe befindliche Schlange
     * sofort wieder stirbt.
     */
    public static final double SPAWN_PROTECTION_SECONDS = 3.0;
}
