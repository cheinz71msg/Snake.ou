package org.example.snake.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Repräsentiert eine Schlange auf der Karte - egal ob vom Spieler
 * oder vom Computer (KI) gesteuert.
 *
 * Die Bewegung funktioniert wie bei Wormate.io: Die Schlange bewegt sich
 * immer automatisch nach vorne in Richtung "angle" (Winkel im Bogenmaß).
 * Über die Eingabe "turnInput" (-1 = links, 0 = geradeaus, 1 = rechts)
 * wird der Winkel pro Tick leicht verändert.
 *
 * Der zurückgelegte Weg des Kopfes wird als Liste von Punkten ("path")
 * gespeichert. Der Körper der Schlange besteht einfach aus den letzten
 * Punkten dieses Pfades, solange die Gesamtlänge die aktuelle
 * Schlangenlänge nicht übersteigt.
 */
public class Snake {

    public final String id;
    public String name;
    public final String color;
    public final String secondaryColor;

    /** Für Spieler-Schlangen: 1 oder 2. Für KI-Schlangen: null. */
    public final Integer playerSlot;

    public double angle;
    /** Gewünschte Drehrichtung: -1 (links), 0 (gerade), 1 (rechts). */
    public volatile int turnInput = 0;
    public volatile boolean boosting = false;
    /**
     * Für die Touch-Joystick-Steuerung: Statt eines diskreten turnInput
     * sendet der Client hier direkt die gewünschte Weltrichtung (Bogenmaß).
     * Ist dieses Feld gesetzt (nicht null), berechnet {@link GameEngine#tick}
     * daraus jeden Tick serverseitig (ohne Netzwerk-Latenz, analog zur
     * KI-Steuerung) den passenden turnInput. Wird von einer Tastatur-
     * Steuernachricht (reines turn ohne desiredAngle) wieder auf null
     * gesetzt, um zurück in den direkten turnInput-Modus zu wechseln.
     */
    public volatile Double manualDesiredAngle = null;

    // --- Pro-Schlange-Zustand für die KI-Steuerung (AiController ist eine
    // geteilte Instanz für alle Computer-Schlangen, daher darf der Zustand
    // nicht dort, sondern muss hier pro Schlange gespeichert werden). ---
    public double aiWanderTimer = 0;
    public int aiWanderTurn = 0;
    public boolean aiAvoidingWall = false;

    // --- Zusätzlicher KI-Zustand zur Erkennung und Vermeidung von
    // endlosem Kreisen um ein Futterziel (siehe AiController.update()). ---
    public String aiTargetFoodId = null;
    public double aiTargetBestDist = Double.MAX_VALUE;
    public double aiTargetStuckTimer = 0;
    public String aiBlacklistFoodId = null;
    public double aiBlacklistTimer = 0;
    public double aiBreakFreeTimer = 0;
    public int aiBreakFreeDir = 1;

    public double length;
    public boolean alive = true;

    /**
     * Sekunden verbleibender Spawn-Schutz (Unverwundbarkeit direkt nach dem
     * Spawnen). Zählt in GameEngine.tick() pro Tick herunter; solange > 0
     * nimmt diese Schlange nicht an Kollisionsprüfungen teil (weder als
     * Opfer noch als Hindernis für andere).
     */
    public double spawnProtectionTimer = GameConfig.SPAWN_PROTECTION_SECONDS;

    /**
     * Zwischengespeicherter, ausgedünnter Körper (siehe {@link #sampledBody}).
     * Wird von der GameEngine einmal pro Tick aktualisiert und dann mehrfach
     * (Kollisionsprüfung, KI-Ausweichlogik, Zustands-Snapshot) wiederverwendet,
     * statt den rohen Pfad jedes Mal neu abzutasten - das war zuvor der
     * größte CPU-Fresser bei vielen Schlangen.
     */
    public List<Vector2> cachedBody = List.of();

    /** Pfad der Kopfposition, neuester Punkt zuerst. */
    private final Deque<Vector2> path = new ArrayDeque<>();
    private double pathArcLength = 0.0;

    public Snake(String id, String name, String color, String secondaryColor, Integer playerSlot, Vector2 startPos, double startAngle) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.secondaryColor = secondaryColor;
        this.playerSlot = playerSlot;
        this.angle = startAngle;
        this.length = GameConfig.START_LENGTH;
        this.path.addFirst(startPos.copy());
    }

    public Vector2 head() {
        return path.peekFirst();
    }

    /** Bewegt die Schlange für einen Tick vorwärts und passt die Drehung an. */
    public void advance(double dt) {
        double maxTurnRadians = Math.toRadians(GameConfig.TURN_RATE_DEG_PER_SEC) * dt;
        angle += turnInput * maxTurnRadians;
        // Winkel im Bereich [-PI, PI] halten
        if (angle > Math.PI) angle -= 2 * Math.PI;
        if (angle < -Math.PI) angle += 2 * Math.PI;

        double speed = GameConfig.BASE_SPEED * (boosting ? GameConfig.BOOST_MULTIPLIER : 1.0);
        Vector2 oldHead = head();
        Vector2 newHead = new Vector2(
                oldHead.x + Math.cos(angle) * speed * dt,
                oldHead.y + Math.sin(angle) * speed * dt
        );

        double segmentLen = oldHead.distanceTo(newHead);
        path.addFirst(newHead);
        pathArcLength += segmentLen;

        trimPath();
    }

    /** Entfernt alte Pfadpunkte, deren Bogenlänge über die aktuelle Schlangenlänge hinausgeht. */
    private void trimPath() {
        while (path.size() > 2 && pathArcLength > length) {
            Vector2 last = path.peekLast();
            Vector2 beforeLast = null;
            Iterator<Vector2> it = path.descendingIterator();
            it.next(); // = last
            if (it.hasNext()) {
                beforeLast = it.next();
            }
            if (beforeLast == null) break;
            double segLen = last.distanceTo(beforeLast);
            if (pathArcLength - segLen < length) {
                break; // würden wir ihn entfernen, wäre die Schlange zu kurz -> stehen lassen
            }
            path.removeLast();
            pathArcLength -= segLen;
        }
    }

    /**
     * Liefert eine vereinfachte (ausgedünnte) Liste von Körperpunkten für
     * Darstellung & Kollisionsprüfung, mit ungefähr "spacing" Welt-Einheiten
     * Abstand zwischen den Punkten.
     */
    public List<Vector2> sampledBody(double spacing) {
        List<Vector2> result = new ArrayList<>();
        double acc = 0;
        Vector2 prev = null;
        for (Vector2 p : path) {
            if (prev == null) {
                result.add(p);
                prev = p;
                continue;
            }
            acc += prev.distanceTo(p);
            if (acc >= spacing) {
                result.add(p);
                acc = 0;
            }
            prev = p;
        }
        return result;
    }

    /** Alle rohen Pfadpunkte (für genaue Kollisionsprüfung), neuester zuerst. */
    public List<Vector2> rawPath() {
        return new ArrayList<>(path);
    }

    public void grow(double amount) {
        this.length += amount;
    }

    public boolean isPlayer() {
        return playerSlot != null;
    }

    /** True, solange der frisch gespawnte Schlange-Schild aktiv ist (siehe spawnProtectionTimer). */
    public boolean isSpawnProtected() {
        return spawnProtectionTimer > 0;
    }
}
