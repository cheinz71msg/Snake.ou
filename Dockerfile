# --- Build-Stufe: Projekt mit Maven bauen ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Abhängigkeiten zuerst herunterladen (nutzt den Docker-Layer-Cache,
# solange sich die pom.xml nicht ändert -> schnellere Rebuilds)
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# --- Laufzeit-Stufe: nur das fertige Jar + ein schlankes JRE ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Render setzt die Umgebungsvariable PORT automatisch; Spring Boot liest sie
# über application.properties (server.port=${PORT:8080}) aus.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
