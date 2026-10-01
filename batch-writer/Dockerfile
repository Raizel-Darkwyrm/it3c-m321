# Stufe 1: Das Modul mit Java 21 aus dem gemeinsamen Maven-Reaktor bauen.
# Der Build-Kontext ist die Projektwurzel, damit das Eltern-POM verfügbar ist.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Maven benötigt auch das POM des nicht ausgewählten Nachbarmoduls.
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Testcontainers-Tests werden separat ausserhalb des Image-Builds ausgeführt.
# Dieser Schalter ersetzt den noch offenen Testnachweis nicht.
RUN mvn -q -pl batch-writer -am package -DskipTests

# Stufe 2: Nur die Laufzeit und das gebaute JAR werden ausgeliefert.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
# Kein HTTP-Port: Der Writer empfängt ausschliesslich über RabbitMQ.
# Die Exec-Form leitet das Stop-Signal direkt an den Java-Prozess weiter.
ENTRYPOINT ["java", "-jar", "app.jar"]
