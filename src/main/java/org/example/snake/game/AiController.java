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

        // 2. Gefahr durch andere Schlangen auf dem Weg voraus?
        Integer dangerTurn = avoidOtherSnakes(snake, engine);
        if (dangerTurn != null) {
            snake.turnInput = dangerTurn;
            return;
        }

        // Blacklist-Timer für zuvor als "Kreis-Falle" erkanntes Futter
        // herunterzählen, damit es nach einer Weile wieder angesteuert werden darf.
        if (snake.aiBlacklistTimer > 0) {
            snake.aiBlacklistTimer -= dt;
            if (snake.aiBlacklistTimer <= 0) {
                snake.aiBlacklistFoodId = null;
            }
        }

        // 3. Steckt die KI gerade in einem bewussten "Ausbruch" aus einer
        // erkannten Kreisbewegung? Dann diesen zuerst zu Ende führen.
        if (snake.aiBreakFreeTimer > 0) {
            snake.aiBreakFreeTimer -= dt;
            snake.turnInput = snake.aiBreakFreeDir;
            snake.boosting = true; // schneller aus der Schleife herausfahren
            return;
        }

        // 4. Nächstes Futter anvisieren - mit Fortschritts-Überwachung:
        // Kommt die KI dem Ziel trotz Ansteuerns über längere Zeit nicht
        // näher, kreist sie offenbar nur darum herum (zu enger Kurvenradius
        // bei der aktuellen Geschwindigkeit) -> Futter sperren und bewusst
        // in eine feste Richtung ausbrechen, statt endlos weiterzukreisen.
        Food target = findNearestFood(snake, engine);
        if (target != null) {
            double dist = head.distanceTo(target.position);
            if (!target.id.equals(snake.aiTargetFoodId)) {
                snake.aiTargetFoodId = target.id;
                snake.aiTargetBestDist = dist;
                snake.aiTargetStuckTimer = 0;
            } else if (dist < snake.aiTargetBestDist - 1.5) {
                snake.aiTargetBestDist = dist;
                snake.aiTargetStuckTimer = 0;
            } else {
                snake.aiTargetStuckTimer += dt;
            }

            if (snake.aiTargetStuckTimer > GameConfig.AI_FOOD_STUCK_SECONDS) {
                snake.aiBlacklistFoodId = target.id;
                snake.aiBlacklistTimer = GameConfig.AI_FOOD_BLACKLIST_SECONDS;
                snake.aiBreakFreeTimer = GameConfig.AI_BREAK_FREE_SECONDS;
                snake.aiBreakFreeDir = random.nextBoolean() ? -1 : 1;
                snake.aiTargetFoodId = null;
                snake.aiTargetStuckTimer = 0;
                snake.turnInput = snake.aiBreakFreeDir;
                snake.boosting = true;
                return;
            }

            double desiredAngle = Math.atan2(target.position.y - head.y, target.position.x - head.x);
            snake.turnInput = turnTowards(snake.angle, desiredAngle);
            return;
        }
        snake.aiTargetFoodId = null;

        // 5. Nichts gefunden -> sanft wandern (meist geradeaus mit leichten
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

    /**
     * Prüft, ob der Kurs der Schlange sie in eine andere Schlange führen
     * würde, und liefert ggf. die Ausweichrichtung (-1/1).
     *
     * Zwei Verbesserungen gegenüber der ursprünglichen, zu "blinden" Version:
     * 1. Der Vorschauabstand skaliert mit der aktuellen Geschwindigkeit
     *    (schnelle/boostende Schlangen brauchen mehr Reaktionsstrecke, sonst
     *    "übersehen" sie Hindernisse, weil sie schneller sind als ihre
     *    eigene Vorschau reicht).
     * 2. Es wird nicht nur EIN Punkt exakt in Blickrichtung geprüft, sondern
     *    mehrere Strahlen leicht versetzt links/rechts davon, UND pro
     *    Strahl der Abstand zur gesamten Strecke Kopf->Vorschaupunkt (nicht
     *    nur zu dessen Endpunkt). Das vorherige Verfahren übersah häufig
     *    Hindernisse, die knapp neben dem exakten Vorschaupunkt lagen oder
     *    dichter am Kopf als der volle Vorschauabstand - das war die
     *    Hauptursache dafür, dass Schlangen "nicht auswichen".
     */
    private Integer avoidOtherSnakes(Snake snake, GameEngine engine) {
        Vector2 head = snake.head();
        double speed = GameConfig.BASE_SPEED * (snake.boosting ? GameConfig.BOOST_MULTIPLIER : 1.0);
        double lookAheadDist = Math.max(GameConfig.AI_AVOID_LOOKAHEAD_MIN, speed * GameConfig.AI_AVOID_LOOKAHEAD_SECONDS);
        double dangerRadius = GameConfig.AI_AVOID_DANGER_RADIUS;

        double[] probeAngleOffsets = {0.0, -0.2, 0.2, -0.45, 0.45};
        for (double offset : probeAngleOffsets) {
            double probeAngle = snake.angle + offset;
            Vector2 ahead = new Vector2(
                    head.x + Math.cos(probeAngle) * lookAheadDist,
                    head.y + Math.sin(probeAngle) * lookAheadDist
            );
            for (Snake other : engine.getSnakes().values()) {
                // Der eigene Körper ist seit der Abschaltung der Selbstkollision
                // ungefährlich -> die KI muss ihm nicht mehr ausweichen (das hat
                // zuvor bei langen, aufgerollten Schlangen zu Endlos-Kreisen geführt).
                // Schlangen mit aktivem Spawn-Schutz sind "Geister" - man kann
                // nicht mit ihnen kollidieren, die KI muss ihnen also auch nicht ausweichen.
                if (!other.alive || other == snake || other.isSpawnProtected()) continue;
                for (Vector2 p : other.cachedBody) {
                    if (p.distanceToSegment(head, ahead) < dangerRadius) {
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
            // Futter überspringen, das gerade als "Kreis-Falle" erkannt und
            // gesperrt wurde (siehe update()), damit die KI nicht sofort
            // wieder dieselbe Kreisbewegung startet.
            if (f.id.equals(snake.aiBlacklistFoodId)) continue;
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
        return staticTurnTowards(currentAngle, desiredAngle);
    }

    /**
     * Statische Variante derselben Logik, wiederverwendet von
     * {@link GameEngine#tick(double)} für manuell gesteuerte Schlangen mit
     * Touch-Joystick (siehe dort): Der Client sendet dort direkt die
     * gewünschte Weltrichtung statt eines diskreten Turn-Werts, und der
     * Server löst daraus jeden Tick lokal (ohne Netzwerk-Latenz) die
     * nötige Drehrichtung auf - genau wie hier für die KI.
     */
    public static int staticTurnTowards(double currentAngle, double desiredAngle) {
        double diff = desiredAngle - currentAngle;
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff < -Math.PI) diff += 2 * Math.PI;
        if (Math.abs(diff) < 0.05) return 0;
        return diff > 0 ? 1 : -1;
    }
}
