package org.example.snake.game;

/**
 * Einfacher 2D-Vektor / Punkt in der Spielwelt.
 * Wird sowohl für Positionen als auch Richtungen genutzt.
 */
public class Vector2 {

    public double x;
    public double y;

    public Vector2(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double distanceTo(Vector2 other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public Vector2 copy() {
        return new Vector2(x, y);
    }
}
