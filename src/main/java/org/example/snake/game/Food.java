package org.example.snake.game;

/**
 * Ein Futter-Objekt auf der Karte. Schlangen wachsen, wenn ihr Kopf
 * ein Futter-Objekt berührt.
 */
public class Food {

    public final String id;
    public final Vector2 position;
    public final double radius;
    /** Farbe als Hex-String (für das Frontend), z. B. "#ff5555". */
    public final String color;

    public Food(String id, Vector2 position, double radius, String color) {
        this.id = id;
        this.position = position;
        this.radius = radius;
        this.color = color;
    }
}
