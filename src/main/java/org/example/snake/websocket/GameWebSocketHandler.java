package org.example.snake.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import org.example.snake.game.GameEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Nimmt WebSocket-Verbindungen der Browser-Clients entgegen, verarbeitet
 * eingehende Steuerbefehle ("join", "control", "leave") und sendet den
 * Spielzustand an alle verbundenen Clients (Broadcast).
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private GameEngine gameEngine;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    /** Pro Session: Mapping von Spieler-Slot (1 oder 2) auf die zugehörige Schlangen-ID. */
    private final Map<WebSocketSession, Map<Integer, String>> sessionSlots = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        sessionSlots.put(session, new ConcurrentHashMap<>());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        Map<Integer, String> slots = sessionSlots.remove(session);
        if (slots != null) {
            slots.values().forEach(gameEngine::removeSnake);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Map<?, ?> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String type = String.valueOf(payload.get("type"));
        Map<Integer, String> slots = sessionSlots.get(session);
        if (slots == null) return;

        switch (type) {
            case "join" -> {
                int slot = ((Number) payload.get("slot")).intValue();
                String name = payload.get("name") != null ? String.valueOf(payload.get("name")) : null;
                String skinId = payload.get("skin") != null ? String.valueOf(payload.get("skin")) : null;
                String snakeId = gameEngine.addPlayerSnake(slot, name, skinId);
                slots.put(slot, snakeId);
                sendTo(session, Map.of("type", "joined", "slot", slot, "snakeId", snakeId));
            }
            case "control" -> {
                int slot = ((Number) payload.get("slot")).intValue();
                boolean boost = Boolean.TRUE.equals(payload.get("boost"));
                String snakeId = slots.get(slot);
                if (snakeId != null) {
                    Object angleVal = payload.get("angle");
                    if (angleVal instanceof Number) {
                        // Touch-Joystick: gewünschte Weltrichtung statt diskretem turn
                        gameEngine.setControlAngle(snakeId, ((Number) angleVal).doubleValue(), boost);
                    } else {
                        int turn = ((Number) payload.get("turn")).intValue();
                        gameEngine.setControl(snakeId, turn, boost);
                    }
                }
            }
            case "leave" -> {
                int slot = ((Number) payload.get("slot")).intValue();
                String snakeId = slots.remove(slot);
                if (snakeId != null) {
                    gameEngine.removeSnake(snakeId);
                }
            }
            default -> {
                // unbekannter Nachrichtentyp wird ignoriert
            }
        }
    }

    /** Sendet eine Nachricht (als Objekt, wird zu JSON serialisiert) an alle verbundenen Clients. */
    public void broadcast(Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            return;
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            sendRaw(session, message);
        }
    }

    private void sendTo(WebSocketSession session, Object payload) throws IOException {
        sendRaw(session, new TextMessage(objectMapper.writeValueAsString(payload)));
    }

    /**
     * Tomcat erlaubt pro Session immer nur einen gleichzeitigen Schreibvorgang.
     * Da sowohl der Spiel-Loop (Broadcast) als auch eingehende Client-Nachrichten
     * (z. B. die "joined"-Antwort) auf unterschiedlichen Threads senden können,
     * wird hier pro Session synchronisiert, um Konflikte zu vermeiden.
     */
    private void sendRaw(WebSocketSession session, TextMessage message) {
        synchronized (session) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(message);
                }
            } catch (IOException ignored) {
                // Session evtl. gerade geschlossen worden - einfach überspringen
            }
        }
    }
}
