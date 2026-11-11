# Spezifikation: Live-Zustellung – Bewertung 2

Stand: 11.11.2026. Diese Spezifikation ist die verbindliche Grundlage für die
Implementierung. Massgeblich bleiben der Bewertungsauftrag, `CLAUDE.md`, das bestehende
JSON-Format des `chat-service` und die acht Szenarien S1 bis S8.

## 1. Zweck

Der `chat-service` stellt angenommene Nachrichten sofort per Server-Sent Events (SSE)
an alle aktuell verbundenen Clients desselben Raums zu. Die Live-Zustellung ist vom
Schreibweg nach PostgreSQL unabhängig. Jede skalierte Instanz erhält deshalb eine eigene
Kopie jeder Nachricht vom vorhandenen Fanout-Exchange `chat.delivery`.

Nicht Bestandteil sind WebSocket, `web-gateway`, Keycloak, eine Benutzeroberfläche,
Raumverwaltung, Mitgliedschaften und eine dauerhafte Chat-Historie.

## 2. Bestehender Eingangsvertrag

`POST /messages` und die Erzeugung der fertigen Nachricht bleiben unverändert. Der
`chat-service` veröffentlicht dieselbe Nachricht in `chat.persist` und am Fanout-Exchange
`chat.delivery`. Der Exchange heisst verbindlich `chat.delivery`.

Das JSON enthält genau diese sechs Vertragsfelder:

| Feld | JSON-Typ | Bedeutung |
|---|---|---|
| `id` | String im UUID-Format | Vom Server vergebene Nachrichten-ID |
| `roomId` | String im UUID-Format | Raum, an dessen Clients zugestellt wird |
| `senderId` | String | Technische Absenderkennung |
| `senderName` | String | Anzeigename zum Sendezeitpunkt |
| `content` | String | Nachrichteninhalt |
| `sentAt` | String im ISO-8601-Format | Vom Server vergebener UTC-Zeitpunkt |

Der Vertrag ist das JSON und keine gemeinsam genutzte Java-Klasse. Der SSE-Ausgang gibt
dieselben Felder und Werte aus, die auch nach `chat.persist` gesendet werden.

## 3. SSE-Vertrag

Der öffentliche Endpunkt innerhalb des Docker-Netzes lautet:

```http
GET /stream?roomId=<UUID>
Accept: text/event-stream
Last-Event-ID: <UUID>   # optional
```

Eine erfolgreiche Verbindung antwortet mit `Content-Type: text/event-stream`. Jedes
Nachrichtenereignis besitzt dieses Format:

```text
id: <Nachrichten-UUID>
data: {"id":"...","roomId":"...","senderId":"...","senderName":"...","content":"...","sentAt":"..."}

```

Es wird kein eigener Ereignisname verlangt. `id:` und das JSON in `data:` gehören immer
zur selben Nachricht. Eine ungültige `roomId` oder `Last-Event-ID` ergibt HTTP 400. Eine
syntaktisch gültige, aber im Replay-Puffer nicht mehr bekannte `Last-Event-ID` ergibt
HTTP 409. Ohne `Last-Event-ID` werden keine alten Nachrichten geliefert.

**Entscheidung L1:** SSE wird direkt im `chat-service` angeboten.

- **Warum?** Genau dieser feste Endpunkt wird bewertet; Gateway und Oberfläche sind ausgeschlossen.
- **Verhindert:** Zusätzliche Dienste, Ports oder ein vom Prüfskript nicht erreichbarer Vertrag.
- **Szenarien:** S2, S3, S5 und S7.

## 4. Instanzqueue und Fanout

Jede `chat-service`-Instanz deklariert beim Start genau eine eigene Queue. Der Name wird
pro Prozess eindeutig erzeugt. Die Queue ist nicht dauerhaft, exklusiv an ihre
RabbitMQ-Verbindung gebunden und automatisch löschbar. Sie ist an `chat.delivery`
gebunden und besitzt genau einen Consumer.

Queue-Eigenschaften:

| Eigenschaft | Wert |
|---|---|
| durable | `false` |
| exclusive | `true` |
| auto-delete | `true` |
| `x-max-length` | `1000` |
| `x-overflow` | `drop-head` |

Bei 1000 wartenden Nachrichten verwirft RabbitMQ beim nächsten Eingang die älteste
wartende Nachricht dieser Instanzqueue. Andere Instanzqueues, `chat.persist` und
`chat.dlq` sind davon nicht betroffen. Es gibt für Live-Zustellungen keine DLQ.

Der angeforderte RabbitMQ-Heartbeat beträgt fünf Sekunden. Verschwindet eine Instanz
ohne geordneten Stop, erkennt RabbitMQ die verlorene Verbindung und löscht die exklusive
Queue. Beim geordneten Skalieren wird sie unmittelbar mit der Verbindung entfernt.

**Entscheidung L2:** Eine Queue pro Instanz statt einer gemeinsamen Queue.

- **Warum?** Fanout muss jede Instanz erreichen, weil nur sie ihre lokalen SSE-Clients kennt.
- **Verhindert:** Competing Consumers, bei denen ein Client Nachrichten verliert, sobald die Kopie bei einer anderen Instanz landet.
- **Szenarien:** S3 und S4.

**Entscheidung L3:** Rückstau wird auf 1000 begrenzt und `drop-head` verwendet.

- **Warum?** Eine pausierte Anzeigeinstanz darf den Broker nicht unbegrenzt füllen; aktuelle Live-Nachrichten sind nützlicher als der älteste Rückstau.
- **Verhindert:** Unbegrenztes Brokerwachstum und Auswirkungen auf die gesunde Instanz.
- **Szenarien:** S6.

## 5. Raumfilter und Normalbetrieb

Jede Instanz konsumiert alle Nachrichten ihrer Instanzqueue. Sie prüft `roomId` nicht am
Broker, sondern lokal. Eine Nachricht wird einmal an jede offene SSE-Verbindung dieser
Instanz gesendet, deren `roomId` gleich ist. Verbindungen anderer Räume erhalten nichts.

Die Instanz verarbeitet ihre Queue mit genau einem Consumer. RabbitMQ bewahrt die
Reihenfolge eines Publishers in einer Queue; die lokale Verarbeitung und Zustellung
bleiben ebenfalls seriell. Damit bleiben die Nachrichten eines Absenders in der
Reihenfolge, in der dessen sendende Instanz sie publiziert hat.

Eine empfangene UUID wird lokal nur einmal verarbeitet. Eine erneute Brokerzustellung
derselben UUID erzeugt deshalb kein zweites SSE-Ereignis.

**Entscheidung L4:** Raumfilterung erfolgt erst in jeder Instanz.

- **Warum?** Ein Fanout-Exchange kennt keine Routing-Keys; jede Instanz kennt nur ihre eigenen Clients.
- **Verhindert:** Nachrichten aus Raum A bei Clients aus Raum B.
- **Szenarien:** S3.

## 6. Replay-Puffer und Neuverbindung

Jede Instanz hält im Arbeitsspeicher die letzten 1000 unterschiedlichen Nachrichten
global in Empfangsreihenfolge. Der Puffer ist nach UUID duplikatsicher. Beim 1001. Eintrag
wird der älteste entfernt. Er wird weder in PostgreSQL gespeichert noch zwischen
Prozessen geteilt. Da jede gesunde Instanz dieselben Fanout-Nachrichten erhält, kann ein
Client zu jeder gesunden Instanz zurückkehren, solange seine Cursor-ID noch im dortigen
Puffer liegt.

Bei einer Neuverbindung mit `Last-Event-ID` sucht die Instanz diese UUID im Puffer. Ist
sie vorhanden und gehört zum angefragten Raum, sendet sie danach genau die später
empfangenen Nachrichten desselben Raums. Die Cursor-Nachricht selbst wird nicht wiederholt.
Anschliessend läuft dieselbe Verbindung live weiter.

Suche, Replay und Registrierung für neue Live-Ereignisse werden unter demselben lokalen
Zustandsschutz ausgeführt. Währenddessen kann die RabbitMQ-Zustellung keine Nachricht
dazwischenschieben. So entsteht weder eine Lücke noch eine doppelte Übergabe zwischen
Replay und Live-Modus.

Ist die ID unbekannt, verdrängt oder gehört sie zu einem anderen Raum, antwortet der
Dienst mit HTTP 409. Der Client muss dann ohne Cursor neu verbinden. Das ist keine
dauerhafte Historienfunktion.

**Entscheidung L5:** Begrenzter lokaler Replay-Puffer statt Datenbankabfrage.

- **Warum?** Live-Zustellung und Neuverbindung bleiben unabhängig von PostgreSQL; für S7 reichen 50 fehlende Nachrichten sicher in den 1000er-Puffer.
- **Verhindert:** Lücken durch asynchron noch nicht persistierte Nachrichten und Ausfallkopplung an `batch-writer` oder PostgreSQL.
- **Szenarien:** S5 und S7.

## 7. Client- und Zustellfehler

Eine SSE-Verbindung wird bei normalem Abschluss, Timeout oder Sendefehler aus der lokalen
Verbindungsliste entfernt. Ein fehlerhafter Client beendet weder den RabbitMQ-Consumer
noch die Zustellung an andere Clients. Die Nachricht bleibt zuvor im Replay-Puffer, damit
eine Neuverbindung sie nachholen kann.

Der RabbitMQ-Listener bestätigt eine Lieferung erst, nachdem sie in den lokalen Puffer
übernommen und an die zu diesem Zeitpunkt erreichbaren Clients übergeben wurde. Ein
Clientabbruch ist kein Grund für Broker-Requeue. Unerwartete interne Fehler dürfen die
Lieferung dagegen fehlschlagen lassen; die UUID-Deduplizierung macht eine erneute
Zustellung unschädlich.

## 8. Skalierung, Pause und abhängige Dienste

- Bei zwei Instanzen existieren genau zwei gebundene Instanzqueues mit je einem Consumer.
- Beim Skalieren auf eine Instanz verschwindet die zweite Queue spätestens nach 30 Sekunden.
- Wird eine Instanz pausiert, wächst ihre Queue niemals über 1000 wartende Nachrichten.
- Die gesunde Instanz erhält ihre eigene Kopie aller Nachrichten weiterhin vollständig.
- Nach dem Fortsetzen und einer nötigen AMQP-Neuverbindung deklariert die Instanz ihre Queue erneut und liefert neue Nachrichten wieder aus.
- Sind `batch-writer` und PostgreSQL gestoppt, sammelt `chat.persist` weiter. Der Fanout- und SSE-Weg bleibt aktiv; nach dem Neustart wird der Persistenzrückstau geschrieben.
- Fällt RabbitMQ aus, können weder POST-Publikation noch Live-Zustellung erfolgreich sein. Nach Wiederverbindung werden Queue und Binding erneut deklariert.

## 9. Nebenläufigkeit und Speichergrenzen

Replay-Puffer und SSE-Verbindungen gehören nur zu einer JVM. Ein einfacher gemeinsamer
Zustandsschutz serialisiert Anmeldung, Replay, Deduplizierung und Live-Verteilung. Es gibt
keine Synchronisation zwischen Instanzen. RabbitMQ-Fanout liefert jeder Instanz ihre Kopie.

Der Nachrichtenpuffer ist auf 1000 Einträge begrenzt. Beendete oder fehlerhafte Emitter
werden entfernt. Nachrichteninhalte werden nicht in Logs geschrieben. Logs nennen nur
technische IDs, Queue, Raum, Verbindungsereignis und Fehlerklasse.

## 10. Konfiguration und Compose

Es werden keine neuen Secrets oder Pflichtvariablen benötigt. Die vorhandenen RabbitMQ-
Variablen bleiben gültig. `chat-service` besitzt keinen `container_name`, damit
`--scale chat-service=2` funktioniert. Kein Dienst veröffentlicht einen Host-Port. Alle
Dienste liegen im Netz `chat-net`; das Prüfskript verbindet sich von dort mit jeder
Instanz einzeln.

## 11. Abnahmekriterien S1 bis S8

| Szenario | Messbare Erwartung | Konkrete Prüfung |
|---|---|---|
| S1 | Ein Root-Lauf vollständig grün | `mvn clean test` |
| S2 | Frischer Stack läuft; keine veröffentlichten Ports | `.env` aus `.env.example`, dann `docker compose up -d --build`; `docker compose ps` und `docker compose config` prüfen |
| S3 | Zwei A-Clients erhalten je 400 einmalig und geordnet pro Absender; B erhält 0 | Zwei Instanzen skalieren, deren IPs im `chat-net` bestimmen, drei SSE-Verbindungen öffnen, je 200 POSTs an beide Instanzen senden und IDs/Inhalte zählen |
| S4 | Zuerst zwei Instanzqueues mit je einem Consumer, nach Scale-down binnen 30 s eine; persist/dlq bleiben | `rabbitmqctl list_queues name consumers` vor und nach `docker compose up -d --scale chat-service=1` wiederholt prüfen |
| S5 | 100 Nachrichten binnen 5 s live; nach Neustart 100 DB-Zeilen | Writer und DB stoppen, SSE öffnen, 100 POSTs senden und Zeit/IDs messen; Dienste starten und per SQL nach IDs zählen |
| S6 | Pausenqueue höchstens 1000; gesunder Client erhält 5000; fortgesetzte Instanz liefert neue Nachrichten | Eine von zwei Instanzen pausieren, 5000 senden, Queue-Tiefen und Client-IDs messen, Instanz fortsetzen und Kontrollnachricht senden |
| S7 | Nach Cursor exakt 50 fehlende Nachrichten in Reihenfolge, keine doppelt, danach live | Erstes Ereignis-ID speichern, Client trennen, 50 senden, 10 s warten, mit `Last-Event-ID` neu verbinden und danach Kontrollnachricht senden |
| S8 | Keine Streams; jede eigene Klasse/Methode erklärt; `.env` nicht getrackt | `rg "\.stream\(|parallelStream|java\.util\.stream" chat-service/src`; statisches Kommentarreview; `git ls-files -- .env` |

## 12. Kritische Prüfung gegen die Szenarien

| Szenario | Stelle | Reicht das? | Hauptrisiko |
|---|---|---|---|
| S1 | 11/S1 | Ja, wenn alle Testcontainers-Prüfungen in einem Lauf stabil sind | Zu lange oder voneinander abhängige Integrationstests |
| S2 | 10 und 11/S2 | Ja | Versehentlicher Host-Port oder nicht bereite Queue-Deklaration |
| S3 | 4, 5 und 11/S3 | Ja | Gemeinsame statt eigener Queue oder parallele Listener verändern Reihenfolge |
| S4 | 4 und 8 | Ja | Verbindung bleibt nach Instanzverlust länger als 30 s bestehen |
| S5 | 2 und 8 | Ja | Anzeigeweg wird versehentlich an DB-Verfügbarkeit gekoppelt |
| S6 | 4 und 8 | Ja | Fehlendes Queue-Limit oder Overflow blockiert den Publisher |
| S7 | 3 und 6 | Ja | Race zwischen Replay und Live-Registrierung |
| S8 | 9 und 11/S8 | Ja | Neue Helfermethoden oder Tests ohne erklärenden Kommentar |

## 13. Abgrenzung des Nachweises

Die Spezifikation verspricht keine Exactly-once-Garantie über Prozessabstürze hinweg und
keine unbegrenzte Wiederherstellung. Sie erfüllt den bewerteten Live-Vertrag innerhalb
des begrenzten Replay-Fensters. Der endgültige Testbericht darf bestandene Szenarien erst
nach einem tatsächlich ausgeführten Lauf behaupten.
