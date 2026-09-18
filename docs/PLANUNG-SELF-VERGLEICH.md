# Vergleich mit der Repository-Planung

Stand: 18.09.2026. Die eigene Planung befindet sich separat in [PLANUNG-SELF.md](PLANUNG-SELF.md). Dieser Vergleich unterscheidet zwischen dem geplanten Zielbild und dem vorhandenen Code.

Die eigene Planung konzentriert sich auf ein überschaubares, vollständig funktionierendes Chat-MVP. Die Repository-Planung legt mehr Gewicht auf verteilte Verarbeitung und Skalierung. Java 21, Spring Boot, React, TypeScript, Keycloak, PostgreSQL, RabbitMQ und Docker Compose sind in beiden Entwürfen vorgesehen.

## Unterschiede im Zielbild

| Thema | Eigene Planung | Repository-Planung |
|---|---|---|
| Hauptziel | Erst vollständiges MVP, später erweitern | Verteilung und Skalierung mit einem Ziel von 100'000+ Nachrichten pro Minute demonstrieren |
| Backend | Ein Chat-Backend für Geschäftslogik, Sicherheit und Datenbankzugriff | Getrennte Dienste: Gateway, Chat-Service und Batch-Writer |
| Speicherung | Chat-Backend schreibt direkt in PostgreSQL | RabbitMQ puffert Nachrichten; Batch-Writer schreibt gesammelt |
| RabbitMQ | Events für weitere Verarbeitung | Zentraler Transportweg für Speicherung und Live-Zustellung |
| Datenbankzugriff | Spring Data JPA | JdbcTemplate mit Batch-Inserts im Writer |
| Live-Updates | Zuerst REST und Polling, später WebSocket | WebSocket im geplanten Nachrichtenfluss |
| Token-Prüfung | Im Chat-Backend | Am Gateway; innere Dienste vertrauen dem internen Netz |
| Web-Einstieg | React mit Nginx oder vergleichbarem Reverse Proxy | Spring-Boot-Gateway mit REST, WebSocket und Auth-Proxy |
| Desktop-Client | Spätere Erweiterung | JavaFX als zweiter Client vorgesehen |
| Lasttests | Skalierung später | Lastgenerator und Anzeige der Queue-Tiefe vorgesehen |
| Migrationen | Flyway festgelegt | Werkzeug noch nicht konkret festgelegt |
| Keycloak-Datenhaltung | Separate keycloak-db vorgesehen | Eigene Datenhaltung erwähnt, weniger konkret beschrieben |
| Volumes | Benannte Volumes für PostgreSQL, Keycloak-Datenbank und RabbitMQ geplant | Aktuelles Compose definiert keine expliziten benannten Volumes |
| Build-System | Maven oder Gradle noch offen | Maven-Multi-Modul-Projekt |

Die README nennt nginx für das Gateway, während die ausführliche PLANUNG.md einen Spring-Boot-Dienst beschreibt. Der Vergleich richtet sich nach der ausführlichen Planung.

## Wichtigster Unterschied: Nachrichtenfluss

Eigene Planung:

```text
Browser → Reverse Proxy → Chat-Backend → PostgreSQL
                                |
                                +→ RabbitMQ-Event → weitere Verarbeitung
```

Das Backend speichert selbst und veröffentlicht ein Ereignis. Der Empfänger lädt im MVP neue Nachrichten per Polling. Die Absicherung zwischen Datenbankschreiben und Event-Veröffentlichung ist noch nicht konkret festgelegt.

Repository-Planung:

```text
Browser → Gateway → Chat-Service → RabbitMQ
                                      |      |
                                      |      +→ Gateway → Empfänger
                                      +→ Batch-Writer → PostgreSQL
```

RabbitMQ ist Teil des Speicherwegs. Zustellung und Speicherung sind entkoppelt: Eine Nachricht kann beim Empfänger eintreffen, bevor sie in der Datenbank steht. HTTP 202 bedeutet angenommen, nicht bereits gespeichert oder zugestellt.

Der geplante Writer sammelt bis zu 500 Nachrichten oder schreibt nach 200 ms. Er bestätigt die Verarbeitung erst nach erfolgreichem Datenbank-Commit. UUIDs und `ON CONFLICT DO NOTHING` sollen doppelte Datenbankeinträge bei erneuter Zustellung verhindern. Dieser Writer ist noch nicht implementiert.

## Was die eigene Planung zusätzlich konkretisiert

- Flyway für versionierte Datenbankmigrationen.
- Separate Keycloak-Datenbank und benannte Docker-Volumes.
- Funktionaler MVP mit Chatübersicht, Historie und Autorisierung.
- Ausführliche Begründung der Technologieentscheidungen.

## Was die Repository-Planung zusätzlich konkretisiert

- Batch-Writer als einziger Schreiber für Chat-Daten.
- `chat.persist` als Queue für den Speicherweg.
- `chat.delivery` als Fanout-Exchange für Live-Zustellung.
- `chat.dlq` für endgültig fehlgeschlagene Verarbeitung.
- Batch-Verarbeitung, erneute Zustellung und Duplikatbehandlung.
- Lastgenerator, Queue-Tiefe und Skalierung mehrerer Instanzen.
- Keycloak-Proxy unter `/auth/**`, damit der Login über denselben öffentlichen Port erreichbar ist. Die eigene Planung sieht den Weg über die Web-App grundsätzlich vor, konkretisiert den Auth-Proxy aber noch nicht.

## Tatsächlicher Implementierungsstand

Vorhanden sind nur der Chat-Service und die RabbitMQ-Anbindung als erste Ausbaustufe:

- Java 21, Spring Boot 3.5.16 und das Maven-Modul `chat-service`.
- `POST /messages` mit Prüfung erforderlicher Felder.
- Vergabe einer UUID und eines Serverzeitstempels.
- JSON-Versand an `chat.persist` und `chat.delivery`.
- Queue-, Exchange- und Dead-Letter-Konfiguration.
- HTTP 503 bei behandelten AMQP-Ausnahmen.
- Docker Compose mit RabbitMQ und Chat-Service ohne veröffentlichte Host-Ports.
- Unit- und Integrationstests, teilweise mit Testcontainers.

Noch fehlen Gateway, Weboberfläche, Login, PostgreSQL-Anbindung, Migrationen, Historie, Batch-Writer, WebSocket-Zustellung, Desktop-Client und Lastgenerator. Raumzugehörigkeit und Berechtigungen werden noch nicht geprüft. Absenderdaten kommen derzeit aus der Anfrage.

Die beiden Veröffentlichungen an RabbitMQ sind getrennte Aufrufe; eine gemeinsame Alles-oder-nichts-Absicherung ist nicht implementiert. Das Durchsatzziel ist noch nicht nachgewiesen.

Die Tests wurden für die Analyse gelesen, aber nicht ausgeführt. Die Anwendung wurde dabei nicht gestartet.

## Konsequenz für die eigene Planung

Wenn ein schrittweise aufgebautes Chat-MVP das Ziel ist, passt die eigene Planung dazu. Wenn Batch-Verarbeitung und 100'000+ Nachrichten pro Minute verbindlich sind, müssten folgende Punkte ergänzt werden:

1. Schreiben der Chatnachrichten in einen eigenen Batch-Writer auslagern.
2. RabbitMQ in den eigentlichen Speicherweg aufnehmen.
3. Speicherung und Live-Zustellung als unabhängige Abläufe beschreiben.
4. Wiederholungen, Zustellgarantien und Duplikatbehandlung festlegen.
5. Lastgenerator und messbare Erfolgskriterien vorsehen.
6. Zuständigkeiten für Gateway, Token-Prüfung und Autorisierung klären.

Flyway, persistente Volumes und die Beschreibung der Berechtigungen können aus der eigenen Planung übernommen werden. Polling bleibt ein möglicher Zwischenschritt, weicht aber vom geplanten WebSocket-Ablauf ab.

## Grundlagen des Vergleichs

- [Eigene Planung](PLANUNG-SELF.md), übernommen aus `PLANUNG - Self.md`.
- [Repository-Planung](../PLANUNG.md).
- [README](../README.md).
- [Docker Compose](../docker-compose.yml).
- [Maven-Elternprojekt](../pom.xml).
- [Chat-Service-Modul](../chat-service/pom.xml).
- Java-Quellcode und Tests unter `chat-service/src`.

