package org.example.snake.game;

import java.util.List;
import java.util.Random;

/**
 * Steuert eine Computer-Schlange (KI). Die KI verfolgt eine einfache Logik:
 * 1. Suche das nächste Futter in der Nähe und steuere darauf zu.
 * 2. Weiche Kartenrändern aus, bevor sie zu nah kommen.
 * 3. Weiche anderen Schlangen aus, wenn sie dem eigenen Kopf im Weg sind.
 * Wenn nichts davon zutrifft, wird in sanften Kurven "gewandert".
 */
public class AiController {

    private final Random random = new Random();

    public void update(Snake snake, GameEngine engine, double dt) {
        Vector2 head = snake.head();

        // 1. Gefahr durch Kartenrand? (mit Hysterese: einmal ausgelöst, erst
        // wieder stoppen wenn deutlich mehr Abstand da ist - verhindert ein
        // Flackern/Kreisen direkt an der Randzonen-Grenze.)
        Integer wallAvoidTurn = avoidWalls(snake);
        if (wallAvoidTurn != null) {
            snake.turnInput = wallAvoidTurn;
            snake.boosting = false;
            return;
        }

        // 2. Gefahr durch andere Schlangen direkt voraus?
        Integer dangerTurn = avoidOtherSnakes(snake, engine);
        if (dangerTurn != null) {
            snake.turnInput = dangerTurn;
            return;
        }

        // 3. Nächstes Futter anvisieren
        Food target = findNearestFood(snake, engine);
        if (target != null) {
            double desiredAngle = Math.atan2(target.position.y - head.y, target.position.x - head.x);
            snake.turnInput = turnTowards(snake.angle, desiredAngle);
            return;
        }

        // 4. Nichts gefunden -> sanft wandern (meist geradeaus mit leichten
        // Schlenkern, damit die Schlange nicht in engen Kreisen rotiert).
        snake.aiWanderTimer -= dt;
        if (snake.aiWanderTimer <= 0) {
            snake.aiWanderTimer = 0.8 + random.nextDouble() * 1.2;
            double r = random.nextDouble();
            snake.aiWanderTurn = r < 0.55 ? 0 : (r < 0.775 ? -1 : 1);
        }
        snake.turnInput = snake.aiWanderTurn;
    }

    private Integer avoidWalls(Snake snake) {
        Vector2 head = snake.head();
        double margin = snake.aiAvoidingWall ? 420 : 220;
        boolean nearLeft = head.x < margin;
        boolean nearRight = head.x > GameConfig.MAP_WIDTH - margin;
        boolean nearTop = head.y < margin;
        boolean nearBottom = head.y > GameConfig.MAP_HEIGHT - margin;

        if (!nearLeft && !nearRight && !nearTop && !nearBottom) {
            snake.aiAvoidingWall = false;
            return null;
        }
        snake.aiAvoidingWall = true;

        double centerX = GameConfig.MAP_WIDTH / 2.0;
        double centerY = GameConfig.MAP_HEIGHT / 2.0;
        double desiredAngle = Math.atan2(centerY - head.y, centerX - head.x);
        return turnTowards(snake.angle, desiredAngle);
    }

    private Integer avoidOtherSnakes(Snake snake, GameEngine engine) {
        Vector2 head = snake.head();
        double lookAheadDist = 130;
        Vector2 ahead = new Vector2(
                head.x + Math.cos(snake.angle) * lookAheadDist,
                head.y + Math.sin(snake.angle) * lookAheadDist
        );

        double dangerRadius = GameConfig.SNAKE_RADIUS * 3;
        for (Snake other : engine.getSnakes().values()) {
            // Der eigene Körper ist seit der Abschaltung der Selbstkollision
            // ungefährlich -> die KI muss ihm nicht mehr ausweichen (das hat
            // zuvor bei langen, aufgerollten Schlangen zu Endlos-Kreisen geführt).
            if (!other.alive || other == snake) continue;
            List<Vector2> body = other.cachedBody;
            for (int i = 0; i < body.size(); i++) {
                if (ahead.distanceTo(body.get(i)) < dangerRadius) {
                    // Teste, ob links oder rechts freier ist
                    double leftAngle = snake.angle - Math.toRadians(45);
                    double rightAngle = snake.angle + Math.toRadians(45);
                    Vector2 leftProbe = new Vector2(head.x + Math.cos(leftAngle) * lookAheadDist,
                            head.y + Math.sin(leftAngle) * lookAheadDist);
                    Vector2 rightProbe = new Vector2(head.x + Math.cos(rightAngle) * lookAheadDist,
                            head.y + Math.sin(rightAngle) * lookAheadDist);
                    double leftClearance = distanceToNearestBody(leftProbe, engine, snake);
                    double rightClearance = distanceToNearestBody(rightProbe, engine, snake);
                    return leftClearance > rightClearance ? -1 : 1;
                }
            }
        }
        return null;
    }

    private double distanceToNearestBody(Vector2 point, GameEngine engine, Snake self) {
        double min = Double.MAX_VALUE;
        for (Snake s : engine.getSnakes().values()) {
            if (!s.alive || s == self) continue;
            for (Vector2 p : s.cachedBody) {
                double d = point.distanceTo(p);
                if (d < min) min = d;
            }
        }
        return min;
    }

    private Food findNearestFood(Snake snake, GameEngine engine) {
        Vector2 head = snake.head();
        double sightRadius = 900;
        Food nearest = null;
        double nearestDist = sightRadius;
        for (Food f : engine.getFoods().values()) {
            double d = head.distanceTo(f.position);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = f;
            }
        }
        return nearest;
    }

    /** Berechnet -1/0/1, um sich möglichst schnell auf desiredAngle zu drehen. */
    private int turnTowards(double currentAngle, double desiredAngle) {
        double diff = desiredAngle - currentAngle;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        if (Math.abs(diff) < 0.05) return 0;
        return diff > 0 ? 1 : -1;
    }
}
