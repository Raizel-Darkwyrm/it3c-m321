# M321 — Chat-App (Klasse IT3c)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Bauen, testen, starten

Stand 02.10.2026 nach Docker-Nachholung: Der vollständige Root-Testlauf besteht
mit 141 Tests einschliesslich echter RabbitMQ-/PostgreSQL-Integrationstests.
Image-Build, Frischstart und S2–S7 wurden praktisch geprüft; S4 benötigt bei
1.000 wartenden Nachrichten 6 gemessene DB-Transaktionen. Die Compose-Prüfung
erfolgte nach Skriptkorrekturen in Fortsetzungsläufen. S8 wurde manuell/statisch
geprüft und für die vier Kriterien bestanden: keine Streams, Kommentare über allen
eigenen Klassen und Methoden, `.env` nicht getrackt. Details und Grenzen stehen
im [Prüfprotokoll](docs/test-batch-writer.md).

Aus der Projektwurzel, mit laufendem Docker und Compose sowie Java 21/Maven:

```bash
mvn clean test
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 batch-writer
```

Die Integrationstests benötigen RabbitMQ und PostgreSQL über Testcontainers.
Die Dockerfiles überspringen Tests beim Image-Bau; ein erfolgreicher Image-Build
ersetzt deshalb keinen Maven-Testlauf. Für einen späteren reproduzierbaren
Frischstart einen separaten Testklon mit neuen Testvolumes verwenden; dort vor
jedem Compose-Unterbefehl `--env-file .env.example` angeben, beispielsweise
`docker compose --env-file .env.example up -d --build`. Nicht gleichzeitig mit
einem anderen Projektstack im fest benannten Netzwerk `chat-net` betreiben.

Der `chat-service` veröffentlicht bewusst **keinen Port** auf den Host. Der einzige offene Port
des Gesamtsystems gehört später dem Gateway.

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21, SSE | Nimmt Nachrichten per `POST /messages` an und liefert sie mit `GET /stream` raumbezogen live aus | Bewertung 2 implementiert; Gesamtabnahme noch offen |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21, JdbcTemplate | Speichert Nachrichten aus `chat.persist` stapelweise | implementiert; S1–S8 nachgewiesen, Ablaufgrenzen siehe Prüfprotokoll |
| postgres | PostgreSQL 16 | Tabelle `message` für persistierte Nachrichten | Schema, Compose-Betrieb, Ausfall/Erholung und Datenerhalt geprüft |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | nginx | Einziger nach aussen offener Port | folgt |
| Web-UI | React | Browser-Client | folgt |

Der aktuelle Compose-Stack enthält vier Dienste in `chat-net`, alle ohne
veröffentlichte Host-Ports. Keycloak, Gateway, Web-UI und eine Chat-Historien-API
sind nicht Bestandteil der Bewertung-1-Implementierung. Das Netzwerk ist nicht
mit `internal: true` konfiguriert; fehlende Host-Port-Mappings bedeuten keine
vollständige Netzisolation.

## Konfiguration und Datenhaltung

Die lokale `.env` bleibt ausserhalb von Git und Docker-Build-Kontext. Vorhandene
Dateien nicht mit der Vorlage überschreiben. Die Beispielpasswörter sind für den Unterricht.

| Variable | Verwendung |
|---|---|
| `RABBITMQ_USER` | Broker-Benutzer für chat-service und Writer |
| `RABBITMQ_PASSWORD` | Broker-Passwort; Compose setzt daraus `RABBITMQ_DEFAULT_PASS` |
| `POSTGRES_USER` | PostgreSQL-Benutzer und Writer-Anmeldung |
| `POSTGRES_PASSWORD` | Datenbankpasswort |
| `POSTGRES_DB` | Name der Datenbank |
| `RABBITMQ_HOST` | Von Compose fest auf `rabbitmq` gesetzt, kein zusätzlicher `.env`-Eintrag |
| `POSTGRES_HOST` | Von Compose fest auf `postgres` gesetzt, kein zusätzlicher `.env`-Eintrag |

Compose setzt aus `RABBITMQ_USER` den Broker-Wert `RABBITMQ_DEFAULT_USER`.
AMQP verwendet intern Port 5672 und Vhost `/`, PostgreSQL Port 5432.
Der Writer hat keinen HTTP-Server. Batch-Grösse 500, Sammelfrist 200 ms,
Prefetch 500 und ein Consumer je Writer sind feste Vorgaben, keine `.env`-Schalter.

Das Skript [`postgres/init/001-create-message.sql`](postgres/init/001-create-message.sql)
erstellt `message` beim ersten PostgreSQL-Start mit leerem Datenverzeichnis.
Der Writer führt keine Schema-Initialisierung aus. Ein vorhandenes Volume wird
durch Änderungen am Init-Skript nicht migriert. Die benannten Volumes
`postgres-data` und `rabbitmq-data` erhalten Daten bei normalem Neustart und
Container-Ersatz. Ein normales `docker compose down` entfernt sie nicht;
`down -v` würde sie löschen und gehört nicht zum Prüfablauf. Änderungen an
Initialisierungs-Zugangsdaten konfigurieren bestehende Datenvolumes nicht neu.

## Intern prüfen und zwei Writer betreiben

Interner Nachrichtenfluss, Queue-Zustand, Schema und Betrieb mit zwei Writern
wurden im Compose-Stack geprüft; die folgenden Befehle dienen der Diagnose.
HTTP-Aufrufe erfolgen aus einem temporären Client im Docker-Netz. Das folgende
PowerShell-Beispiel übergibt den JSON-Body über stdin, damit auch unter Windows
PowerShell 5.1 die Anführungszeichen erhalten bleiben:

```powershell
'{"roomId":"02daf19f-8f95-436c-a202-21e6f24b0172","senderId":"review-user","senderName":"Review","content":"README-Pruefung"}' | docker run --rm -i --network chat-net curlimages/curl:8.10.1 -i -X POST http://chat-service:8080/messages -H 'Content-Type: application/json' --data-binary '@-'
```

Erwartung: HTTP 202 mit serverseitiger Nachrichten-ID; das bestätigt die Annahme,
noch nicht den DB-Commit. Diese ID für die spätere Datenbankprüfung aufbewahren.
Queue-Zustand und Schema lassen sich ohne Host-Ports prüfen:

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name consumers messages_ready messages_unacknowledged
docker compose exec postgres psql -U chat -d chat -c '\d public.message'
docker compose exec postgres psql -U chat -d chat -c 'SELECT id, room_id, content FROM message;'
docker compose up -d --scale batch-writer=2
docker compose ps batch-writer
docker compose exec rabbitmq rabbitmqctl list_queues name consumers messages_ready messages_unacknowledged
```

`-U chat -d chat` gilt für die unveränderte Beispielvorlage; bei eigener `.env`
Benutzer und Datenbank entsprechend ersetzen. SELECT dient nur der Diagnose und
darf nicht während des S4-Messfensters laufen. Bei zwei Writern werden genau zwei
Consumer auf `chat.persist` erwartet. Beide teilen Queue und Tabelle, haben aber
eigene Puffer und Verbindungen. Eine Verteilung von exakt 50:50 ist nicht verlangt.
Mit `docker compose up -d --scale batch-writer=1` lässt sich wieder eine Instanz betreiben.

## Live-Zustellung aus Bewertung 2

Jede `chat-service`-Instanz besitzt eine eigene exklusive Queue am Fanout-Exchange
`chat.delivery`. Dadurch sieht jede Instanz jede Nachricht und kann sie an ihre eigenen
SSE-Clients verteilen. Die Queue hält höchstens 1000 wartende Live-Kopien; bei Überlauf
entfernt RabbitMQ die älteste. Beim Ende einer Instanz verschwindet ihre Queue automatisch.

Der Stream ist nur im internen Netz erreichbar:

```http
GET /stream?roomId=<UUID>
Accept: text/event-stream
Last-Event-ID: <UUID>
```

`Last-Event-ID` ist optional. Eine bekannte ID liefert die späteren Nachrichten desselben
Raums aus dem lokalen 1000er-Replay-Puffer und wechselt danach wieder in den Live-Modus.
Eine unbekannte oder verdrängte ID ergibt HTTP 409. Jedes Ereignis enthält die
Nachrichten-ID in `id:` und das vollständige sechs Felder umfassende JSON in `data:`.

Zum Skalieren bleibt `chat-service` ohne festen Containernamen und ohne Host-Port:

```bash
docker compose --env-file .env.example up -d --build --scale chat-service=2
docker compose exec rabbitmq rabbitmqctl list_queues name consumers messages_ready
```

Die Live-Zustellung benötigt weder PostgreSQL noch den `batch-writer`. Nachrichten warten
bei deren Ausfall weiterhin in `chat.persist`, während der SSE-Weg über `chat.delivery`
aktiv bleibt. Gezielte Integrationstests decken zwei Instanzen mit 400 Nachrichten,
Neuverbindung mit 50 Nachrichten, die 1000er-Rückstaugrenze und 100 Live-Nachrichten
ohne Persistenzdienste ab. Der vollständige S1–S8-Nachweis folgt im Testprotokoll.

## Speicherung und Fehlerverhalten

Der implementierte Ablauf sammelt höchstens 500 Nachrichten; ein Teilstapel wird
nach spätestens 200 ms Sammelzeit zur Verarbeitung freigegeben. Das ist keine
Garantie für einen DB-Commit innerhalb von 200 ms bei Ausfall oder laufendem Retry.
Ein Stapel wird in einer Datenbanktransaktion gespeichert. ACK folgt erst nach
erfolgreichem Commit. `ON CONFLICT (id) DO NOTHING` macht erneute Zustellungen
derselben ID unschädlich, ohne bestehende Inhalte zu überschreiben.

Bei allgemeinen Datenbankschreibfehlern sind pro Verarbeitungszyklus höchstens
drei Versuche mit je fünf Sekunden Pause vorgesehen. Jeder Versuch erhält eine
neue Transaktion. Danach werden offene Lieferungen mit Requeue erneut zugestellt;
ein DB-Ausfall allein führt nicht nach `chat.dlq`. Bei einem sicher erkannten
SQL-Datenfehler wird der Batch zurückgerollt und jede Nachricht in einer eigenen
Transaktion verarbeitet. Gültige Nachrichten und Duplikate erhalten nach Commit
ein ACK; nur dauerhaft fehlerhafte Nachrichten gelangen in `chat.dlq`.
Bereits beim Decodieren ungültige Nachrichten werden ohne Schreibversuch dorthin
verwiesen. Auch bei Kanalverlust und Stop können unbestätigte Lieferungen erneut
zugestellt werden. Eine automatische Rückführung aus `chat.dlq` gibt es nicht.

Logs enthalten Instanzkennung, Stapelgrösse, Versuch und Fehlerziel; sie ersetzen
keinen Abgleich von IDs, DB-Zeilen und Queue-Zustand. Die Ausfall-, Zeit- und
Mehrinstanzprüfungen wurden mit echten Diensten nachgeholt. Bei S7 wurde
PostgreSQL nach etwa 15 Sekunden wieder gestartet; beide Writer arbeiteten ohne
Neustart weiter. Auch der Datenerhalt beim Ersetzen der Infrastrukturcontainer
wurde bestätigt.

## Prüfstand für Bewertung 1

Die Abnahmekriterien und konkreten Prüfungen stehen in
[`docs/spec-batch-writer.md`, Kapitel 19](docs/spec-batch-writer.md#19-abnahmekriterien).
Die [Nachholliste im Umsetzungsplan](docs/plan-batch-writer.md#offene-prüfungen-zum-nachholen)
enthält die historische Zurückstellung und verweist auf die nachgeholten Prüfungen.
Das [Prüfprotokoll](docs/test-batch-writer.md) dokumentiert den vollständigen
Maven-Lauf mit 141 Tests, 0 Fehlern, 0 Fehlschlägen und 0 übersprungenen Tests,
die praktischen Compose-Nachweise für S2–S7 und die bestandene manuelle/statische
S8-Prüfung. Die Compose-Nachweise stammen aus Fortsetzungsläufen; ein einziger
ununterbrochener Lauf des endgültigen Abnahmeskripts wird nicht behauptet.
Das [Prüfskript](scripts/test-batch-writer.ps1) ist für einen frischen Testklon ohne
persönliche `.env` und ohne bestehendes `chat-net` oder Testvolumes vorbereitet:
`powershell -NoProfile -File scripts/test-batch-writer.ps1`.
Es nutzt `.env.example`, einen kurzlebigen Python-Client im internen Netz und
bewahrt Messdaten privat unter `Erklärungen`. Der Docker-Ablauf wurde praktisch
ausgeführt und gezielt korrigiert; Voraussetzungen und Einschränkungen stehen
im Prüfprotokoll.

S3 prüft 1'000 angenommene IDs innerhalb von 60 Sekunden. S4 misst für 1'000 bereits
wartende Nachrichten höchstens 100 DB-Transaktionen; Prefetch 500 allein beweist
das nicht. S5 prüft rohe Duplikate, S6 zwei Writer, S7 übernimmt diese beiden für
15 Sekunden PostgreSQL-Ausfall. Die abschliessende Reihenfolge und unveränderten
Testdaten sind verbindlich; keine Queues oder Volumes zwischen Szenarien löschen.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — verbindlicher Vertrag und S1–S8-Abnahmekriterien.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Implementierungsaufgaben und offene Nachweise.
- [`docs/spec-live-zustellung.md`](docs/spec-live-zustellung.md) — SSE-, Queue- und Replay-Vertrag für Bewertung 2.
- [`docs/plan-live-zustellung.md`](docs/plan-live-zustellung.md) — vor der Implementierung gepushter Aufgabenplan für Bewertung 2.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
