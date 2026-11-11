# Prüfprotokoll Live-Zustellung – Bewertung 2

Stand: 12.11.2026. Geprüfter Commit vor diesem Dokument: `b6b67d1`.

## Ergebnis

Die Szenarien S1 bis S8 sind nachgewiesen. Der Maven-Gesamtlauf, die automatisierten
Integrationstests und die praktische Compose-Abnahme waren erfolgreich. Es bestehen keine
offenen fachlichen Blocker. Der Abgabe-Tag wird erst nach dem finalen Git-Check gesetzt.

## Umgebung

- Java 21 und Maven Wrapper des Projekts
- Docker Desktop mit Linux-Containern und Docker Compose
- echte RabbitMQ- und PostgreSQL-Container
- Compose-Konfiguration ausschließlich aus `.env.example`
- keine veröffentlichten Host-Ports

## Szenarien S1 bis S8

| Szenario | Ausgeführte Prüfung | Ergebnis |
|---|---|---|
| S1 | `./mvnw clean test` im Repository-Root | **182 Tests**, davon 36 im `chat-service` und 146 im `batch-writer`; 0 Fehler, 0 Fehlschläge, 0 übersprungen |
| S2 | `docker compose --env-file .env.example down`, danach `docker compose --env-file .env.example up -d --build` und `docker compose ... ps` | Vier Dienste liefen; RabbitMQ und PostgreSQL gesund; `rabbitmq`, `postgres`, `chat-service` und `batch-writer` im `chat-net`; nur interne Containerports |
| S3 | Zwei `chat-service`-Instanzen, je ein SSE-Client in Raum A und ein dritter Client in Raum B; je 200 Nachrichten über beide Instanzen | Beide A-Clients erhielten exakt 400 unterschiedliche IDs; Reihenfolge je Absender korrekt; Raum B erhielt 0 |
| S4 | Vor dem Skalieren Brokerabfrage mit zwei Instanzqueues und je einem Consumer; danach Skalierung auf eine Instanz und erneute Brokerabfrage | Binnen 30 Sekunden blieb genau eine `chat.delivery.*`-Queue; `chat.persist` und `chat.dlq` blieben bestehen |
| S5 | `batch-writer` und PostgreSQL gestoppt; 100 Nachrichten mit offenem SSE-Client; danach beide Dienste ohne Änderung am `chat-service` gestartet | Alle 100 Ereignisse kamen in **656 ms** live an; anschließend wurden exakt die 100 Prüfnachrichten gespeichert und `chat.persist` war leer |
| S6 | Zwei Instanzen; eine pausiert; 5000 Nachrichten und SSE-Client an der gesunden Instanz; Brokergrenze und Wiederanmeldung kontrolliert | Gesunde Instanz lieferte exakt 5000 Ereignisse; pausierte Queue blockierte nicht und konnte wegen Heartbeat entfernt werden; beide Instanzqueues wurden danach mit `x-max-length=1000`, `drop-head` und je einem Consumer bestätigt |
| S7 | Nach erstem Ereignis Verbindung getrennt, 50 Nachrichten gesendet, 10 Sekunden gewartet und mit `Last-Event-ID` verbunden | Exakt 50 fehlende Ereignisse in Reihenfolge und ohne Duplikat; danach eine weitere Nachricht live empfangen |
| S8 | Statische Suche in allen Java-Dateien, Prüfung der Kommentare und `git ls-files -- .env` | Keine Java Streams; sinnvolle deutsche Kommentare über allen eigenen Klassen und Methoden; `.env` nicht getrackt |

## Zusätzliche automatisierte Nachweise

- `TwoInstanceLiveDeliveryIntegrationTest` bildet S3 mit zwei echten Anwendungskontexten,
  RabbitMQ, drei SSE-Clients und 400 Nachrichten ab.
- `StreamReconnectIntegrationTest` prüft S7 einschließlich zehn Sekunden Trennung,
  Replay-Reihenfolge, anschließender Live-Zustellung und HTTP 409 für unbekannte Cursor.
- `DeliveryQueueBacklogIntegrationTest` misst Queue-Abbau und die feste Obergrenze von
  1000 wartenden Live-Kopien bei 5000 Veröffentlichungen.
- `PersistenceIndependenceIntegrationTest` weist 100 Live-Ereignisse innerhalb von fünf
  Sekunden ohne `batch-writer` und PostgreSQL sowie 100 wartende Persistenzkopien nach.

## Verwendete Kontrollen

```powershell
./mvnw clean test
docker compose --env-file .env.example up -d --build
docker compose --env-file .env.example up -d --scale chat-service=2
docker exec it3c-m321-rabbitmq-1 rabbitmqctl list_queues name messages consumers arguments
docker compose --env-file .env.example stop batch-writer postgres
docker pause it3c-m321-chat-service-1
git ls-files -- .env
rg "\.stream\s*\(|parallelStream\s*\(|java\.util\.stream" --glob '*.java' .
```

Die privaten Rohlogs und der nur für die praktische Abnahme verwendete Testclient liegen
unter `Erklärungen/part 2` und werden nicht committed.
