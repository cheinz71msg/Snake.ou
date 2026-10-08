package org.example.snake.websocket;

import java.util.List;
import java.util.Map;

import org.example.snake.game.GameConfig;
import org.example.snake.game.GameEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Treibt die Spiel-Simulation an: ruft in fester Taktrate (siehe
 * {@link GameConfig#TICK_RATE}) die Physik der {@link GameEngine} auf
 * und sendet anschließend den aktuellen Zustand per WebSocket an alle Clients.
 */
@Component
public class GameLoop {

    @Autowired
    private GameEngine gameEngine;

    @Autowired
    private GameWebSocketHandler webSocketHandler;

    @Scheduled(fixedRate = 1000 / GameConfig.TICK_RATE)
    public void loop() {
        gameEngine.tick(GameConfig.TICK_INTERVAL_SECONDS);

        webSocketHandler.broadcast(gameEngine.buildStateSnapshot());

        List<GameEngine.DeathEvent> deaths = gameEngine.drainDeathEvents();
        for (GameEngine.DeathEvent event : deaths) {
            webSocketHandler.broadcast(Map.of(
                    "type", "death",
                    "snakeId", event.snakeId,
                    "slot", event.playerSlot == null ? -1 : event.playerSlot,
                    "name", event.name,
                    "length", Math.round(event.finalLength)
            ));
        }
    }
}
