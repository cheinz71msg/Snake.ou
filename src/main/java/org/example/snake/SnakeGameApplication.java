package org.example.snake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Einstiegspunkt der Snake-Webanwendung.
 * Startet einen eingebetteten Webserver (Spring Boot / Tomcat), der sowohl
 * die statischen Frontend-Dateien (HTML/CSS/JS) als auch den WebSocket-Endpunkt
 * für die Echtzeit-Spielkommunikation bereitstellt.
 */
@SpringBootApplication
@EnableScheduling
public class SnakeGameApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnakeGameApplication.class, args);
    }
}
