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

    /** Anzahl der Futter-Objekte, die mindestens auf der Karte vorhanden sein sollen. */
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

    /** Anzahl Einträge in der Bestenliste. */
    public static final int LEADERBOARD_SIZE = 10;

    /**
     * Wie viele "Nacken"-Punkte ab dem Kopf von der Selbstkollisionsprüfung
     * ausgenommen werden, damit die Schlange sich nicht sofort selbst "beißt".
     */
    public static final int SELF_COLLISION_SKIP_SEGMENTS = 8;
}
