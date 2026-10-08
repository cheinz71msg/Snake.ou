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

    /**
     * Kürzester Abstand dieses Punktes zur Strecke zwischen a und b.
     * Wird für die KI-Kollisionsvorschau genutzt: Ein einzelner
     * Punkt-zu-Punkt-Vergleich (nur gegen den Vorschau-Endpunkt) übersieht
     * Hindernisse, die irgendwo AUF DEM WEG dorthin liegen, aber nicht
     * zufällig genau am Endpunkt - der Vergleich gegen die ganze Strecke
     * erkennt solche Fälle zuverlässig.
     */
    public double distanceToSegment(Vector2 a, Vector2 b) {
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double lenSq = abx * abx + aby * aby;
        if (lenSq < 1e-9) {
            return distanceTo(a);
        }
        double t = ((this.x - a.x) * abx + (this.y - a.y) * aby) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double px = a.x + abx * t;
        double py = a.y + aby * t;
        double dx = this.x - px;
        double dy = this.y - py;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public Vector2 copy() {
        return new Vector2(x, y);
    }
}
