# Spezifikation: batch-writer – Bewertung 1

Stand: 01.10.2026. Status: Spezifikation zur Besprechung, noch nicht implementiert oder durch einen Testlauf bestätigt. Dieses Dokument beschreibt das Soll-Verhalten, keinen Umsetzungsplan.

## Grundlagen und Verbindlichkeit

Diese Spezifikation verwendet ausschliesslich das bestehende Repository und den Bewertungsauftrag „M321 · IT3c · Bewertung 1: batch-writer“, Ausgabe 24.09.2026, Seiten 2–6. Der Auftrag liegt dem Verfasser als PDF vor; er ist keine Voraussetzung für das Lesen dieser Spezifikation. Die bewertungsrelevanten Anforderungen sind unten vollständig beschrieben.

Belege im Repository:

- [CLAUDE.md](../CLAUDE.md): Sprache, Verständlichkeit, Kommentare, Java 21, Netzwerk und Geheimnisse.
- [PLANUNG.md](../PLANUNG.md), Abschnitte 2.1, 3.5–3.7 und 4: PostgreSQL 16, JdbcTemplate, Queues, Batches, Zustellverhalten und Datenmodell.
- [Root-pom.xml](../pom.xml): Maven-Elternprojekt, Java 21 und Spring Boot 3.5.16.
- [ChatMessage.java](../chat-service/src/main/java/ch/benedict/m321/chatservice/dto/ChatMessage.java): alle sechs Nachrichtenfelder und Java-Typen.
- [SendMessageRequest.java](../chat-service/src/main/java/ch/benedict/m321/chatservice/dto/SendMessageRequest.java): Pflichtfelder und nicht leere Texte.
- [MessageService.java](../chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessageService.java): Erzeugung von UUID und Serverzeitstempel.
- [MessagePublisher.java](../chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java): Veröffentlichung über Standard-Exchange und Routing-Key `chat.persist`.
- [RabbitConfig.java](../chat-service/src/main/java/ch/benedict/m321/chatservice/config/RabbitConfig.java): dauerhafte Queues, Dead-Letter-Routing und JSON-Konverter.
- [docker-compose.yml](../docker-compose.yml), [.env.example](../.env.example), [application.yml](../chat-service/src/main/resources/application.yml): vorhandene Dienste und Konfiguration.
- [docs/plan-chat-service.md](plan-chat-service.md): Abgrenzung des bestehenden Producers, insbesondere noch keine Publisher Confirms oder Retry-Verarbeitung.

Mit „Entscheidung“ markierte Festlegungen konkretisieren offene Punkte dieser Quellen. Sie sind keine Behauptung, dass entsprechender Code schon existiert. Bei Unterschieden zwischen dem Gesamtentwurf und Bewertung 1 gilt für diese Spezifikation der engere Bewertungsumfang. Der private Arbeitsplan und externe Fachquellen sind keine Grundlage dieses Dokuments.

## 1. Zweck

Der Dienst `batch-writer` konsumiert Chatnachrichten aus RabbitMQ `chat.persist` und speichert sie gesammelt in PostgreSQL in der Tabelle `public.message`. Er ist für diese Chatnachrichten der einzige schreibende Diensttyp; mehrere Instanzen dieses Dienstes sind erlaubt.

Normalfall: Jede gültige, bislang unbekannte Nachrichten-ID ergibt genau eine Datenbankzeile. Bei erneuter Zustellung entsteht keine zusätzliche Zeile. Endgültig nicht verarbeitbare Nachrichten bleiben in `chat.dlq` zur Untersuchung erhalten.

**Entscheidung D1:** Eigener Dienst als Maven-Modul `batch-writer`, Java 21, Spring Boot 3.5.16 und Spring `JdbcTemplate`; kein JPA.

- **Warum machen wir das?** Die Repository-Planung trennt Annahme und Speicherung und sieht JDBC-Batches ausdrücklich vor.
- **Welchen Fehler verhindert das?** Direkte Einzelpersistenz im chat-service würde die verlangte Entkopplung und den Batch-Schreibweg umgehen.
- **Welches Bewertungsszenario hängt davon ab?** S1, S2, S3, S4, S6 und S8.

## 2. Abgrenzung

Enthalten: Queue-Empfang, JSON-Prüfung, Batch-Bildung, Transaktion, Bestätigungen, Duplikatbehandlung, begrenzte Wiederholungen, Dead-Letter-Verarbeitung, Schema und Konfiguration sowie entsprechende Tests.

Ausgeschlossen: Chat-Historie lesen, Raum- und Mitgliederverwaltung, Keycloak, eigenes Login, web-gateway, WebSocket-Zustellung, Benutzeroberfläche, Desktop-Client und load-generator. Es gibt keine HTTP-API des Writers, keine automatische DLQ-Rückführung, keine Administrationsoberfläche und keine neue Retry-Queue.

Das Ziel von 100'000 Nachrichten pro Minute begründet die Architektur; ein Nachweis dieses Durchsatzes ist kein zusätzliches Bewertungsszenario. Ein temporärer Testclient ist kein neuer Anwendungsdienst.

Die Spezifikation verändert weder die REST-Schnittstelle noch den Zustellweg des chat-service. Dessen zwei Publish-Aufrufe und fehlende Publisher Confirms begrenzen die Ende-zu-Ende-Garantie. Die hier beschriebene Wiederholungsverarbeitung beginnt bei einer in `chat.persist` angekommenen Nachricht. Es wird kein Exactly-once für das Gesamtsystem behauptet.

## 3. Eingangsvertrag `chat.persist`

| Eigenschaft | Vertrag |
|---|---|
| Broker | Bestehender RabbitMQ 3.13, im Compose-Netz unter `rabbitmq:5672` |
| Virtual Host | Standard-Vhost `/` |
| Eingangsqueue | `chat.persist`, durable, nicht exklusiv, nicht automatisch gelöscht |
| Veröffentlichungsweg | Standard-Exchange `""`, Routing-Key `chat.persist` |
| Body | Genau ein UTF-8-kodiertes JSON-Objekt pro AMQP-Nachricht |
| Erforderliche Metadaten | AMQP-Eigenschaft `content_type` mit Wert `application/json` |
| Nicht erforderlich | Java-Typheader wie `__TypeId__`, Retry-Header oder ein gemeinsames Java-Artefakt |
| Fehlerqueue | `chat.dlq`, durable, nicht exklusiv, nicht automatisch gelöscht |
| Dead-Letter-Argumente der Eingangsqueue | `x-dead-letter-exchange=""`, `x-dead-letter-routing-key="chat.dlq"` |

Zusätzliche Producer-Metadaten dürfen vorhanden sein; sie bestimmen nicht den Zieltyp der Deserialisierung. Ein vorhandener `content_encoding`-Wert darf UTF-8 bezeichnen; fehlt er, gilt UTF-8. Es ist keine persistente Delivery-Mode-Eigenschaft als Annahmebedingung erforderlich, da S5 nur `content_type` voraussetzt.

Der Writer deklariert beide Queues mit denselben Eigenschaften wie der chat-service, damit seine Funktionsfähigkeit nicht von einem vorherigen HTTP-Aufruf abhängt. Unterschiedliche Queue-Argumente sind ein Konfigurationsfehler, kein Anlass zum Löschen oder Neuanlegen einer bestehenden Queue. `chat.delivery` wird vom Writer weder konsumiert noch benötigt.

**Entscheidung D2:** Der JSON-Body wird ausdrücklich auf einen eigenen `ChatMessage`-Record des Writers abgebildet; Java-Typheader werden dafür nicht benötigt oder ausgewertet.

- **Warum machen wir das?** Im vorhandenen DTO ist JSON als Dienstvertrag beschrieben. S5 liefert keine Java-Typinformationen.
- **Welchen Fehler verhindert das?** Gültige Nachrichten werden nicht wegen fehlender oder auf ein fremdes Paket verweisender Typheader abgelehnt.
- **Welches Bewertungsszenario hängt davon ab?** Vor allem S5, ausserdem S3 und S6.

## 4. JSON-Nachrichtenformat mit allen Feldern und Typen

```json
{
  "id": "c8c8b460-3bbb-4f73-9b8b-c8a7eca89125",
  "roomId": "3f2b1c4e-0000-0000-0000-000000000001",
  "senderId": "anna",
  "senderName": "Anna Muster",
  "content": "Hallo zusammen",
  "sentAt": "2026-10-01T12:00:00Z"
}
```

| Feld | JSON-Typ | Java-Typ | Pflicht und Prüfung | Herkunft |
|---|---|---|---|---|
| `id` | String | `UUID` | Vorhanden, nicht null, gültige UUID in üblicher Bindestrichdarstellung | Vom chat-service erzeugt |
| `roomId` | String | `UUID` | Vorhanden, nicht null, gültige UUID in üblicher Bindestrichdarstellung | Aus der HTTP-Anfrage |
| `senderId` | String | `String` | Vorhanden, nicht null, nicht leer oder ausschliesslich Whitespace | Aus der HTTP-Anfrage |
| `senderName` | String | `String` | Vorhanden, nicht null, nicht leer oder ausschliesslich Whitespace | Aus der HTTP-Anfrage |
| `content` | String | `String` | Vorhanden, nicht null, nicht leer oder ausschliesslich Whitespace | Aus der HTTP-Anfrage |
| `sentAt` | String | `Instant` | Vorhanden, nicht null, ISO-8601-Zeitpunkt mit UTC-Angabe oder Offset, in PostgreSQL darstellbar | Vom chat-service erzeugt |

Feldnamen sind exakt und unterscheiden Gross-/Kleinschreibung. Zahlen, Arrays und Objekte werden nicht stillschweigend in Strings umgewandelt. Unbekannte zusätzliche Felder werden ignoriert; die sechs Pflichtfelder bleiben notwendig. Doppelte Feldnamen im selben JSON-Objekt sind ungültig, damit die Bedeutung eindeutig bleibt. UTF-8-Fehler, ungültiges JSON und Nullzeichen in Textfeldern sind ungültig; letztere lassen sich nicht in PostgreSQL-Textspalten speichern.

Es gibt keine erfundene maximale Textlänge, keine Raumexistenzprüfung und keine Prüfung gegen Keycloak. Nicht leere Texte werden unverändert gespeichert, insbesondere nicht getrimmt. ID und Zeitstempel werden vom Writer nicht neu erzeugt. PostgreSQL speichert Zeitpunkte mit Mikrosekundenpräzision; Tests vergleichen daher den Zeitpunkt auf dieser Präzision, nicht eine möglicherweise längere Nanosekunden-Zeichenfolge.

## 5. Verhalten im Normalbetrieb

1. Der Writer startet, liest die Konfiguration und verbindet sich mit PostgreSQL und RabbitMQ.
2. Er konsumiert `chat.persist` mit manueller Bestätigung und `prefetch=500`.
3. Er prüft jede Lieferung nach Kapitel 3 und 4. Ungültige Lieferungen behandelt er nach Kapitel 11.
4. Gültige Lieferungen bleiben unbestätigt und werden mit ihrem Empfangskanal und ihrer Delivery-ID einem Stapel zugeordnet.
5. Sobald eine Grenze aus Kapitel 6 erreicht ist, wird dieser Stapel geschlossen und in einer Datenbanktransaktion geschrieben.
6. Nach dem erfolgreichen Commit bestätigt der Writer dessen Lieferungen.
7. Der nächste Stapel wird verarbeitet. Ein leerer Stapel erzeugt keine Datenbanktransaktion.

Pro Instanz gibt es genau einen Consumer und eine serialisierte Verarbeitung: Empfangsverwaltung, Timer, Schreiben und Bestätigen dürfen denselben Stapel nicht gleichzeitig verändern. Während ein Stapel geschrieben oder wiederholt wird, wird kein zweiter Stapel parallel in die Datenbank geschrieben. Noch nicht bearbeitete Lieferungen bleiben durch Prefetch begrenzt unbestätigt.

„Serialisiert“ bedeutet nicht, dass der Empfang der ersten Lieferung bis zum Ende des Stapels blockiert werden darf. Nach deren Aufnahme muss der Empfang weiterer Lieferungen möglich bleiben; insbesondere darf ein einzelner Listener-Aufruf nicht auf das Eintreffen von 499 weiteren Nachrichten warten, deren Aufrufe er selbst blockiert. Sammlung und Fristauslösung müssen auch bei ausbleibenden Folgelieferungen vorankommen. Höchstens ein Schreibversuch ist gleichzeitig aktiv.

**Entscheidung D3:** Ein Consumer, `prefetch=500`, höchstens 500 unbestätigte Lieferungen pro Instanz und genau ein aktiver Schreibablauf.

- **Warum machen wir das?** Das entspricht dem geplanten Prefetch und hält Puffer, Transaktion und Kanalzuordnung erklärbar.
- **Welchen Fehler verhindert das?** Unbegrenzter Speicherverbrauch, doppelte Timer-Ausführung und konkurrierende Änderungen an einem Stapel.
- **Welches Bewertungsszenario hängt davon ab?** S3, S4, S6 und S7.

## 6. Batch-Regel: maximal 500 Nachrichten oder spätestens nach 200 ms

Der erste gültige Eintrag eines neuen Stapels startet eine feste Frist von 200 ms. Bei 500 Einträgen wird sofort geschrieben. Andernfalls wird der vorhandene Teilstapel spätestens beim Ablauf dieser Frist zum Schreiben freigegeben. Weitere Einträge verschieben die Frist nicht. Die Frist wird mit einer monotonen Zeitquelle gemessen und auch ohne weitere Lieferung ausgewertet.

Bei bereits wartenden Lieferungen werden diese unmittelbar bis zur Grössengrenze übernommen; nach der ersten Nachricht wird nicht erst 200 ms geschlafen. Ist die Queue vor dem Writer-Start mit 1'000 Nachrichten gefüllt, müssen ausreichend grosse Stapel entstehen, um insgesamt höchstens 100 Datenbanktransaktionen zu benötigen.

Prefetch 500 ist nur die Grenze offener Lieferungen und garantiert keine Stapelgrösse von 500. Bei 1'000 Nachrichten und höchstens 100 Schreibtransaktionen sind durchschnittlich mindestens zehn Nachrichten pro Transaktion nötig; zusätzlicher Datenbankverkehr benötigt ebenfalls Reserve. Zwei volle Stapel sind ein günstiger Fall, keine Zusage. Die 200-ms-Regel darf für S4 nicht deaktiviert werden. Die Einhaltung beider Grenzen muss mit einem echten Rückstau-Test gemessen werden, nicht aus der Prefetch-Einstellung abgeleitet werden.

Die 200 ms begrenzen das Sammeln eines aktiven Stapels. Sie versprechen weder einen abgeschlossenen Commit nach 200 ms noch eine maximale Liegezeit in RabbitMQ. Ist die Datenbank blockiert oder im Retry, gilt Kapitel 10. Ein bereits abgelaufener Teilstapel wird nach Freigabe des Schreibablaufs ohne weiteres Sammelintervall verarbeitet.

**Entscheidung D4:** Grössengrenze 500 und feste Zeitgrenze 200 ms ab dem ersten gültigen Eintrag, ohne Zurücksetzen bei neuen Nachrichten.

- **Warum machen wir das?** Die Grössengrenze spart Transaktionen unter Last; die Zeitgrenze verhindert langes Warten bei geringer Last.
- **Welchen Fehler verhindert das?** Einzel-Inserts im Lastfall und ein kleiner Stapel, der ohne weitere Nachrichten nie geschrieben wird.
- **Welches Bewertungsszenario hängt davon ab?** S3 und insbesondere S4.

Die Rechnung aus PLANUNG.md mit etwa 3,3 Stapeln pro Sekunde gilt für volle 500er-Stapel. Bei gleichmässigen 1'667 Nachrichten pro Sekunde und einem Writer greift die 200-ms-Grenze ungefähr bei 333 Nachrichten. Diese Spezifikation verspricht deshalb keinen festen Faktor 500 für jede Lastsituation.

## 7. Datenbanktransaktion

Jeder Schreibversuch umfasst genau einen abgeschlossenen Stapel und genau eine gemeinsame Transaktion auf einer JDBC-Verbindung. Alle Insert-Operationen werden per `JdbcTemplate.batchUpdate` mit gebundenen Parametern ausgeführt. SQL wird nicht durch Einsetzen von Nachrichteninhalten zusammengesetzt.

Nach erfolgreicher Ausführung folgt der Commit. Erst dessen erfolgreicher Abschluss erlaubt ACKs. Schlägt ein Insert fehl, wird der gesamte Versuch zurückgerollt; ein erfolgreicher Teil des Batches darf nicht separat bestätigt werden. Nach einem Verbindungsabbruch wird die defekte Verbindung verworfen und beim nächsten Versuch eine neue verwendbare Verbindung bezogen.

JDBC-Batch und Transaktion sind zwei verschiedene Dinge: Der Batch bündelt Operationen; die explizite Transaktionsgrenze verhindert einen Commit pro Datensatz. Ein JDBC-Batch muss nicht als genau ein SQL-Statement auf dem Server erscheinen. Massgeblich ist die gemeinsame Transaktion, nicht die Zahl einzelner SQL-Ausführungen.

**Entscheidung D5:** Ein JDBC-Batch pro Transaktion; Commit muss vor der Rückmeldung an die Bestätigungslogik abgeschlossen sein.

- **Warum machen wir das?** S4 begrenzt Transaktionen, nicht bloss Methodenaufrufe. Der gesamte Stapel soll ein gemeinsames Ergebnis haben.
- **Welchen Fehler verhindert das?** Verstecktes Auto-Commit pro Zeile und Bestätigung von Daten, deren Transaktion noch zurückrollen kann.
- **Welches Bewertungsszenario hängt davon ab?** S3, S4, S5 und S7.

## 8. ACK/NACK-Verhalten

| Ergebnis | Datenbank | Broker-Aktion |
|---|---|---|
| Stapel erfolgreich committed | Neue IDs gespeichert; bestehende IDs unverändert | Jede zugehörige Lieferung einzeln ACKen |
| Vorübergehender Schreibfehler, Versuche übrig | Rollback bzw. ungewisser Commit bei Verbindungsabbruch | Noch kein ACK/NACK; Stapel lokal unbestätigt behalten |
| Dritter Schreibversuch fehlgeschlagen | Kein sicher bestätigter Commit | Jede Lieferung des Stapels einzeln NACKen, `requeue=false` |
| Ungültige Nachricht | Kein Insert | Diese Lieferung einzeln NACKen, `requeue=false` |
| Prozess-/Kanalabbruch | Eventuell noch nicht gespeichert oder bereits committed | Kein ACK mehr auf altem Kanal; RabbitMQ kann unbestätigte Lieferungen erneut zustellen |

ACK und NACK werden ausschliesslich auf dem Empfangskanal und mit `multiple=false` ausgeführt. Nach einem Kanalverlust werden sämtliche zu diesem Kanal gehörenden Delivery-IDs und lokalen Lieferungsreferenzen verworfen. Datenbankarbeit darf nicht mit Bestätigungen auf einem neu eröffneten Kanal verknüpft werden. Die wieder zugestellten Nachrichten durchlaufen die normale Duplikatbehandlung.

Ein Fehler beim ACK nach einem nachweislich erfolgreichen Commit ist kein fehlgeschlagener Datenbankschreibversuch. Er darf weder den Schreibversuchszähler erhöhen noch ein NACK zur DLQ auslösen. Die Bestätigungsschleife wird beendet und der betroffene Kanal geschlossen; noch unbestätigte Lieferungen können erneut eintreffen und werden anhand ihrer IDs behandelt. Bereits erfolgreich bestätigte Lieferungen werden nicht erneut bestätigt. Auch ein Fehler beim Senden eines NACK wird als Kanalfehler behandelt, nicht als erfolgreich nachgewiesene DLQ-Übergabe.

Bei einem geordneten Stop werden keine neuen Stapel mehr begonnen. Ein bereits schreibender Stapel darf innerhalb einer begrenzten Stop-Frist fertig werden; nur ein erfolgreicher Commit erlaubt ACKs. Noch offene Lieferungen bleiben unbestätigt, und der Kanal wird geschlossen. Es gibt kein ACK nur deshalb, weil die Anwendung beendet wird.

**Entscheidung D6:** Manuelle Einzelbestätigung nach Commit, keine Sammel-ACKs.

- **Warum machen wir das?** Einzelbestätigungen sind einfacher zu prüfen, besonders wenn ungültige Lieferungen zwischen gültigen liegen.
- **Welchen Fehler verhindert das?** Ein ACK für eine hohe Delivery-ID bestätigt nicht versehentlich ältere, noch ungespeicherte Lieferungen mit.
- **Welches Bewertungsszenario hängt davon ab?** S3, S5, S6 und S7.

Die Gesamtplanung zeigt `NACK mit requeue` bei einem Schreibfehler. Hier wird dieser offene Fehlerpfad bewusst konkretisiert: drei lokale Schreibversuche ohne zwischenzeitliches Requeue, danach endgültiges NACK zur vorhandenen DLQ. So braucht es keinen vermeintlichen Versuchszähler aus dem blossen Redelivery-Flag. Bei Prozessabbruch bleibt die automatische erneute Zustellung erhalten. Eine gemeinsame atomare Transaktion über PostgreSQL und RabbitMQ wird nicht behauptet.

## 9. Duplikatbehandlung

Das Insert enthält `ON CONFLICT (id) DO NOTHING`. Ein Konflikt auf `id` wird als erfolgreich behandelter Datensatz gewertet. Ein Stapel, der nur bereits vorhandene IDs enthält, wird ebenfalls committed und danach bestätigt.

Ein Update-Zähler von null für ein durch den Konflikt übersprungenes Insert ist kein Fehler. Die Verarbeitung darf nicht verlangen, dass die Anzahl neu eingefügter Zeilen der Anzahl zugestellter Nachrichten entspricht. Andernfalls würde gerade das erwartete Duplikat aus S5 fälschlich wiederholt oder in die DLQ geschickt.

Die erste gespeicherte Zeile bleibt unverändert. Trifft dieselbe ID mit verändertem Inhalt erneut ein, wird sie ebenfalls nicht überschrieben. Es gibt keine vorgelagerte SELECT-Abfrage zur Duplikatprüfung. Duplikate innerhalb desselben Stapels und bei zwei parallelen Instanzen müssen ebenso unschädlich sein.

**Entscheidung D7:** UUID als Primary Key und Konfliktbehandlung direkt im Insert.

- **Warum machen wir das?** Ein Absturz nach Commit, aber vor ACK kann eine erneute Zustellung verursachen.
- **Welchen Fehler verhindert das?** Doppelte Zeilen, Primary-Key-Fehler mit anschliessendem Rollback sowie ein Rennen zwischen einer SELECT-Prüfung und einem Insert.
- **Welches Bewertungsszenario hängt davon ab?** S5 und S6, ausserdem S7 bei ungewissem Commit.

At-least-once betrifft die Verarbeitung der Queue-Lieferung. Zwei separate HTTP-Anfragen erzeugen im bestehenden chat-service zwei verschiedene IDs und sind keine Duplikate im Sinne dieser Spezifikation.

## 10. Verhalten bei Datenbankausfall

Ein Stapel erhält höchstens drei Schreibversuche: Erstversuch plus zwei Wiederholungen. Vor jeder Wiederholung werden 5 Sekunden gewartet. Der nächste Versuch verwendet wieder eine vollständige Transaktion für denselben unveränderten Stapel. Zwischen den Versuchen bleibt er unbestätigt.

Ein einzelner Versuch muss einschliesslich Verbindungsbeschaffung, SQL-Ausführung und Commit nach spätestens 5 Sekunden als erfolgreich oder fehlgeschlagen gelten. Die Implementierung muss dazu die beteiligten Verbindungs-, Socket- und SQL-Zeitgrenzen so abstimmen, dass nicht mehrere unabhängige Wartezeiten unkontrolliert addiert werden. Ein überfälliger Versuch wird beendet; ein neuer Versuch darf nicht parallel zur alten Datenbankarbeit auf derselben Verbindung beginnen. Diese Grenze ist mit dem Ausfalltest nachzuweisen.

Damit erreicht ein Stapel bei erreichbarem Broker normalerweise spätestens nach 25 Sekunden ab dem ersten Schreibversuch einen erfolgreichen Commit oder das Ende des dritten Fehlversuchs: dreimal höchstens 5 Sekunden plus zweimal 5 Sekunden Pause. Eine Prozessunterbrechung setzt den lokalen Versuchszähler zurück; die Grenze ist keine globale Zählung über Neustarts hinweg.

Die 25 Sekunden sind eine Obergrenzenrechnung für einen bereits aktiven Stapel unter den festgelegten Zeitgrenzen, kein Nachweis für die Verarbeitung aller 300 Nachrichten. Sammeln, wartende Stapel, Wiederherstellung der Datenbankverbindungen und Broker-Aktionen kommen hinzu. Alle drei Versuche können bei sofortigen Verbindungsfehlern schon ungefähr nach zehn Sekunden erschöpft sein, also vor der Wiederherstellung nach 15 Sekunden. Deshalb ist das Fehlerziel DLQ für S7 wesentlich. Ein Spring-Transaktions-Timeout allein gilt nicht als Nachweis der gesamten Fünf-Sekunden-Grenze; auch Verbindungsbeschaffung und Commit dürfen nicht unbegrenzt blockieren.

Nach dem dritten Fehler geht der gesamte noch unbestätigte Stapel mittels NACK ohne Requeue nach `chat.dlq`. Es gibt keine Aufteilung in Einzel-Inserts zur Fehlersuche. Der Writer bleibt aktiv und verarbeitet nachfolgende Lieferungen. Nach PostgreSQL-Wiederkehr müssen neue gültige Nachrichten ohne manuellen Writer-Neustart wieder gespeichert werden können.

**Entscheidung D8:** Drei lokale Versuche mit 5 Sekunden Pause und begrenzter Versuchsdauer; anschliessend DLQ als ausdrückliches Fehlerziel für den gesamten Stapel.

- **Warum machen wir das?** PLANUNG.md nennt drei Fehlversuche. S7 erlaubt ausdrücklich Datenbank oder einen in der Spezifikation festgelegten anderen Ort. Die vorhandene DLQ bietet diesen Ort ohne weitere Infrastruktur.
- **Welchen Fehler verhindert das?** Endlose Requeue-Schleifen, dauerhaft blockierende Datenbankaufrufe und stilles Verwerfen fehlgeschlagener Nachrichten.
- **Welches Bewertungsszenario hängt davon ab?** S7; das Verhalten muss zusätzlich als echter Integrationstest vorliegen.

**Bewusste Folge:** Nach einem 15-sekündigen Ausfall dürfen Nachrichten bereits in der DLQ liegen. Sie wandern nicht automatisch zurück in die Datenbank. Auch gültige Nachrichten eines fehlgeschlagenen Stapels können dort landen. Das erfüllt das festgelegte Fehlerziel, garantiert aber keine spätere automatische Speicherung aller gültigen Nachrichten.

Für S7 müssen innerhalb von 90 Sekunden ab Beginn des Ausfalls alle 300 eingespeisten Nachrichten-IDs in `message` oder `chat.dlq` nachweisbar sein. `chat.persist` darf dann keine dieser Lieferungen mehr enthalten, auch nicht als unbestätigt. Bei ungewissem Commit kann eine ID bereits in der Datenbank und zusätzlich in der DLQ liegen; die Datenbank enthält dennoch höchstens eine Zeile pro ID. Entscheidend ist die vollständige Vereinigung der IDs, nicht die blosse Summe zweier Zähler.

Die Wiederherstellung von PostgreSQL wird im Test 15 Sekunden nach dessen Stop angestossen, unabhängig davon, ob der Sender bereits fertig ist. RabbitMQ bleibt in S7 erreichbar. Die Kombination eines Broker-Ausfalls während Dead-Lettering und eines Datenbankausfalls ist kein bewertetes Szenario; dafür wird keine verlustfreie atomare Übergabe versprochen.

## 11. Verhalten bei ungültigen Nachrichten

Ungültige Metadaten oder Bodies nach Kapitel 3 und 4 werden vor der Aufnahme in einen Datenbankstapel erkannt. Diese Lieferung wird sofort mit NACK und `requeue=false` abgelehnt. RabbitMQ übernimmt das konfigurierte Dead-Letter-Routing. Der ursprüngliche Body wird nicht umgeschrieben; RabbitMQ darf seine Dead-Letter-Metadaten ergänzen.

Es gibt keinen positiven ACK vor einer selbst programmierten Neuveröffentlichung in die DLQ. Der Dienst verarbeitet anschliessend die nächste Lieferung. Eine ungültige Nachricht darf bereits gesammelte gültige Nachrichten nicht verwerfen oder deren Frist neu starten.

**Entscheidung D9:** Dauerhaft ungültige Eingaben sofort in die bestehende DLQ, ohne drei identische Deserialisierungsversuche.

- **Warum machen wir das?** Wiederholung macht fehlerhaftes JSON nicht gültig. Die drei Versuche gelten für Datenbankschreibfehler.
- **Welchen Fehler verhindert das?** Eine fehlerhafte Nachricht blockiert nicht die Queue und führt nicht zum Verlust eines ansonsten gültigen Stapels.
- **Welches Bewertungsszenario hängt davon ab?** Ergänzende Fehlerfallspezifikation; S5 darf bei gültigem JSON gerade nicht in diesen Fehlerpfad gelangen. Kein zusätzliches offizielles S-Szenario.

Unerwartete Fehler erst beim Schreiben folgen Kapitel 10. Dazu gehören auch permanente Datenbankprobleme wie ein falsch manuell verändertes Schema. Sie werden mit Fehlergrund protokolliert; nach drei Versuchen landet der betroffene Stapel in der DLQ.

## 12. Verhalten mit mehreren batch-writer-Instanzen

Alle Instanzen konsumieren dieselbe Queue `chat.persist` als Competing Consumers und schreiben in dieselbe Tabelle. Keine Instanz deklariert eine exklusive Eingangsqueue oder einen Single-Active-Consumer-Modus. Jede Instanz hat ihren eigenen Consumer-Kanal, Puffer, Timer, Versuchszähler und Datenbankverbindungen.

`docker compose up -d --scale batch-writer=2` muss zwei laufende Consumer erzeugen. RabbitMQ verteilt Lieferungen auf beide; eine exakte Halbierung oder globale Reihenfolge wird nicht verlangt. Bei Ausfall einer Instanz kann die andere erneut zugestellte Nachrichten verarbeiten. Der Datenbank-Primary-Key verhindert doppelte Zeilen auch bei konkurrierenden Inserts.

Die Grenzen 500 Lieferungen und 200 ms gelten je Instanz, nicht gemeinsam für den ganzen Stack. Zwei Instanzen dürfen somit zusammen bis zu 1'000 unbestätigte Lieferungen besitzen. Es wird nach `room_id` weder exklusiv zugeordnet noch gesperrt: Auch Nachrichten desselben Raums dürfen von beiden Instanzen geschrieben werden. Im fortlaufenden Bewertungsdurchlauf bleiben die zwei Instanzen aus S6 für S7 bestehen.

**Entscheidung D10:** Skalierung durch Competing Consumers, ohne gemeinsamen Anwendungspuffer und ohne globales Anwendungsschloss.

- **Warum machen wir das?** Dieses Muster ist in PLANUNG.md vorgegeben und in S6 messbar.
- **Welchen Fehler verhindert das?** Beide Instanzen schreiben nicht absichtlich dieselben Broadcast-Kopien; ein globales Schloss verhindert nicht die gewünschte Parallelität.
- **Welches Bewertungsszenario hängt davon ab?** S6.

## 13. PostgreSQL-Datenmodell

PostgreSQL 16 enthält im durch `POSTGRES_DB` bestimmten Datenbanknamen die Tabelle `public.message`:

| Spalte | Typ | Null erlaubt? | Herkunft |
|---|---|---|---|
| `id` | `uuid` | Nein | `id` |
| `room_id` | `uuid` | Nein | `roomId` |
| `sender_id` | `varchar` ohne künstliche Längenbegrenzung | Nein | `senderId` |
| `sender_name` | `varchar` ohne künstliche Längenbegrenzung | Nein | `senderName` |
| `content` | `text` | Nein | `content` |
| `sent_at` | `timestamptz` | Nein | `sentAt` |

Es gibt keine automatisch erzeugte Ersatz-ID, keine zusätzliche Schreibzeitspalte und keine Benutzer-, Raum- oder Mitgliedertabelle. `sender_name` bleibt entsprechend der Planung als denormalisierter Anzeigename erhalten.

**Entscheidung D11:** Die sechs Spalten aus PLANUNG.md werden übernommen; `room_id` besitzt in Bewertung 1 ausdrücklich keinen Fremdschlüssel.

- **Warum machen wir das?** Der Bewertungsauftrag schliesst Räume und Mitgliedschaften aus, während der Producer beliebige gültige Raum-UUIDs akzeptiert.
- **Welchen Fehler verhindert das?** Gültige Bewertungsnachrichten scheitern nicht an fehlenden Raum-Stammdaten oder einer zusätzlichen, nicht verlangten Tabelle.
- **Welches Bewertungsszenario hängt davon ab?** S3, S4, S5, S6 und S7.

Das ist eine dokumentierte Einschränkung gegenüber dem Gesamtmodell in PLANUNG.md 3.7. Eine spätere Raumverwaltung muss die Einführung und Befüllung dieser Beziehung separat behandeln.

## 14. Primary Key und Index

`message.id` ist der einzige Primary Key. Zusätzlich wird ein B-Tree-Index `message_room_sent_at_idx` auf `(room_id, sent_at DESC)` erstellt. Es wird kein zusätzlicher Unique-Constraint auf Text, Sender oder Raum eingerichtet.

**Entscheidung D12:** Primärschlüssel auf `id` und genau der zusätzliche Raum-/Zeitindex aus der Planung.

- **Warum machen wir das?** Der Primary Key sichert die Idempotenz; der zusätzliche Index übernimmt das vorgegebene Datenmodell, ohne bereits eine Historien-API zu bauen.
- **Welchen Fehler verhindert das?** Wiederholte IDs erzeugen keine Duplikate; Nachrichten mit gleichem Text und verschiedenen IDs werden nicht fälschlich verworfen.
- **Welches Bewertungsszenario hängt davon ab?** S5 und S6; der Index gehört ausserdem zur bewerteten Datenmodellspezifikation.

## 15. Erstellung des Schemas

Das Schema wird künftig durch eine versionierte SQL-Datei `postgres/init/001-create-message.sql` im Repository bereitgestellt. Compose bindet diese Datei schreibgeschützt unter `/docker-entrypoint-initdb.d/001-create-message.sql` in den PostgreSQL-Container ein. Sie erstellt die Tabelle und den Index aus Kapitel 13 und 14. Der Postgres-Dienst verwendet ein benanntes Datenvolume `postgres-data`.

Die Initialisierung erfolgt beim ersten Start mit leerem Datenverzeichnis. Bei einem normalen Neustart mit vorhandenem Volume bleiben Tabelle und Daten erhalten; das Initialisierungsskript wird nicht als wiederkehrende Migration behandelt. Bestehende Datenvolumes mit abweichendem Schema werden nicht automatisch repariert oder gelöscht. Das ist ein expliziter Konfigurationsfehler, der vor der Abnahme geklärt werden muss.

Der Postgres-Healthcheck muss über TCP auf `127.0.0.1:5432` die Bereitschaft für die konfigurierte Datenbank prüfen. Der Writer startet erst nach gesundem Postgres und gesundem RabbitMQ. Das Schema wird nicht durch jede Writer-Instanz erstellt. Daher konkurrieren zwei Writer beim Start nicht um DDL.

**Entscheidung D13:** Einmalige Schema-Initialisierung im Postgres-Container; kein zusätzliches Migrationstool für diese Ausbaustufe.

- **Warum machen wir das?** Bewertung 1 fordert einen reproduzierbaren frischen Stack mit einem festen Schema, aber kein Migrationsframework.
- **Welchen Fehler verhindert das?** Fehlende Tabellen nach einem frischen Klon und konkurrierende Schema-Erstellung durch mehrere Writer.
- **Welches Bewertungsszenario hängt davon ab?** S2 und S6; Daten bleiben auch für die aufeinanderfolgenden Szenarien erhalten.

## 16. Alle benötigten Umgebungsvariablen

Die folgenden Namen bilden den vollständigen benötigten Anwendungsvertrag. Geheimnisse stehen lokal in `.env`; `.env.example` enthält nur funktionierende Unterrichtsbeispiele. Container erhalten Variablen über Compose, nicht durch Einlesen einer im Image eingebauten `.env`.

| Variable | Beispiel bzw. Wert | Empfänger und Verwendung |
|---|---|---|
| `RABBITMQ_USER` | `chat` | Bestehend: chat-service und Writer authentifizieren sich damit; Compose setzt daraus `RABBITMQ_DEFAULT_USER` am Broker |
| `RABBITMQ_PASSWORD` | `bitte-lokal-aendern` | Bestehend: Passwort der AMQP-Verbindung; Compose setzt daraus `RABBITMQ_DEFAULT_PASS` am Broker |
| `RABBITMQ_HOST` | `rabbitmq` | Compose setzt den internen Broker-Namen für chat-service und Writer; kein zusätzlicher Eintrag in `.env.example` notwendig |
| `POSTGRES_USER` | `chat` | Neu in `.env.example`: PostgreSQL-Benutzer und Writer-Datenbankbenutzer |
| `POSTGRES_PASSWORD` | `bitte-lokal-aendern` | Neu in `.env.example`: Datenbankpasswort |
| `POSTGRES_DB` | `chat` | Neu in `.env.example`: Datenbankname |
| `POSTGRES_HOST` | `postgres` | Compose setzt den internen Datenbanknamen für den Writer |

Feste Werte in der Writer-Konfiguration: AMQP-Port 5672, Vhost `/`, PostgreSQL-Port 5432, Queue-Namen, Batch-Grösse 500, Sammelfrist 200 ms, Prefetch 500, ein Consumer, drei Schreibversuche und 5 Sekunden Wiederholungspause. Die Datenbankverbindung ergibt sich aus Host, Port und `POSTGRES_DB`. Technische Timeout-Einstellungen müssen die maximale Versuchsdauer aus Kapitel 10 erfüllen; sie sind keine weiteren Pflichtvariablen.

**Entscheidung D14:** Nur die drei vom Auftrag verlangten neuen Zugangsdaten werden in `.env.example` ergänzt; interne Hosts setzt Compose, die festgelegten Verhaltensgrenzen bleiben Konfiguration im Modul.

- **Warum machen wir das?** Ein frischer Klon soll mit einer einzigen bekannten Vorlage starten. Zusätzliche Schalter sind für die Bewertung nicht nötig.
- **Welchen Fehler verhindert das?** Versteckte lokale Konfigurationsabhängigkeiten, falsche `localhost`-Verbindungen und eingecheckte Zugangsdaten.
- **Welches Bewertungsszenario hängt davon ab?** S2 und S8.

## 17. Docker-/Netzwerk-Anforderungen

Der fertige Stack enthält `rabbitmq`, `chat-service`, `postgres` und `batch-writer`, alle im bestehenden Netzwerk `chat-net`. Kein Dienst veröffentlicht einen Host-Port; es gibt keine `ports:`-Mappings. Der interne RabbitMQ-Management-Port darf für Prüfungen aus `chat-net` benutzt werden.

`batch-writer` wird als Modul in das Eltern-POM aufgenommen und erhält ein eigenes mehrstufiges Dockerfile mit Build-Kontext Projektwurzel. Alle im Maven-Reaktor benötigten Modul-POMs müssen im Build-Kontext vorhanden sein, auch beim bisherigen chat-service-Dockerbuild. Der Writer benötigt keinen Web-Starter oder HTTP-Port. Ein fester `container_name` ist für den skalierbaren Writer unzulässig.

Postgres erhält `postgres-data`; RabbitMQ erhält ein benanntes Volume `rabbitmq-data` für seine Daten. Volumes ersetzen keine Publisher Confirms oder atomare Speicherung über zwei Systeme. Keine Startlogik leert Queues oder Tabellen. Der Writer muss einen PostgreSQL-Neustart durch seine Fehlerbehandlung überstehen; eine Container-Restart-Policy ersetzt diesen Nachweis nicht.

**Entscheidung D15:** Reproduzierbarer Compose-Stack ohne Host-Ports, mit stabilen Service-Namen, benannten Datenvolumes und skalierbarem Writer.

- **Warum machen wir das?** Der Prüfer verwendet exakt `postgres`, `batch-writer` und `chat-net` und startet aus einem frischen Klon.
- **Welchen Fehler verhindert das?** Portkonflikte beim Skalieren, verlorene Daten bei normalem Container-Ersatz und ein nur lokal funktionierender Build.
- **Welches Bewertungsszenario hängt davon ab?** S2, S6 und S7.

## 18. Logging

Log-Meldungen und technische Feldnamen sind gemäss CLAUDE.md Englisch; Kommentare, Javadoc und Dokumentation Deutsch. Logs enthalten keine Passwörter, vollständigen JDBC-Zugangsdaten oder Nachrichteninhalte.

| Ereignis | Level | Erforderliche Angaben |
|---|---|---|
| Consumer bereit | INFO | Instanzkennung, Queue, Prefetch, Batch-Grenzen |
| Stapel committed | INFO | Instanzkennung, Anzahl behandelter Lieferungen, Versuch, Dauer |
| Schreibversuch fehlgeschlagen | WARN | Instanzkennung, Stapelgrösse, Versuch von drei, Fehlerklasse/SQL-State, nächster Schritt |
| Versuche erschöpft | ERROR | Instanzkennung, Stapelgrösse, Ziel `chat.dlq`, Fehlergrund |
| Ungültige Lieferung | WARN | Instanzkennung, Delivery-ID, Nachrichten-ID falls lesbar, Validierungsgrund |
| Kanalverlust und Wiederverbindung | WARN bzw. INFO | Instanzkennung, betroffene Queue, Verbindungszustand |

Eine erfolgreich behandelte Lieferung ist nicht zwingend eine neu eingefügte Zeile. Logs dürfen deshalb die Stapelgrösse nicht als Zahl neuer Zeilen ausgeben, wenn das JDBC-Ergebnis das nicht belegt. Ein gesendetes NACK ist keine bestätigte Messung des DLQ-Inhalts; diese prüft man am Broker.

**Entscheidung D16:** Nachvollziehbare Ereignislogs pro Instanz und Stapel, ohne Inhalts- oder Geheimnislogging.

- **Warum machen wir das?** Batch-Verarbeitung, Retry und Skalierung müssen im Review erklärbar sein.
- **Welchen Fehler verhindert das?** Irreführende Erfolgsmeldungen vor Commit und nicht unterscheidbare Writer-Instanzen.
- **Welches Bewertungsszenario hängt davon ab?** S4, S6, S7 und S8; Logs ergänzen Messungen, ersetzen sie aber nicht.

## 19. Abnahmekriterien

Die folgenden Befehle und Prüfungen beschreiben die spätere Abnahme. Sie wurden beim Schreiben dieser Spezifikation nicht ausgeführt. Java-Testklassen mit den hier genannten Namen sind geforderte künftige Nachweise, noch keine vorhandenen Dateien. Es wird hier kein Testcode implementiert.

### 19.1 Gemeinsame Messregeln

- S1 bis S8 laufen in dieser Reihenfolge. Ab S2 wird derselbe Stack ohne Leeren von Tabellen oder Queues verwendet. Kein `down -v`, kein `TRUNCATE`, kein Queue-Purge zwischen den Szenarien.
- Isolierte Maven-Integrationstests verwenden eigene Container und ersetzen nicht diesen fortlaufenden Compose-Durchlauf. Insbesondere muss S7 auch mit den zwei aus S6 weiterlaufenden Writern bestehen.
- Jedes Sendeszenario verwendet eine neue Raum-UUID als Messmarkierung und zeichnet die angenommenen Nachrichten-IDs auf. Bestehende Zeilen zählen nicht mit. Gesamtzähler allein beweisen keine Vollständigkeit.
- Die Frist für S3 läuft ab der letzten erfolgreichen HTTP-Annahme; S7 wird strenger ab Beginn des Datenbankausfalls gemessen. Erfolgsprüfungen erfolgen mindestens alle zwei Sekunden, bis Erfolg oder Fristende erreicht ist.
- „Queue leer“ bedeutet für `chat.persist` sowohl `messages_ready=0` als auch `messages_unacknowledged=0`. Bereits in der DB sichtbare Zeilen allein beweisen keine erfolgreichen ACKs.
- `chat.dlq` wird zwischen Szenarien nicht geleert. Es werden Anfangsbestand und neue IDs verglichen.
- Ein zusätzlicher Diagnoseabruf der DLQ muss `ack_requeue_true` verwenden. Er darf Nachrichten nicht dauerhaft konsumieren und darf nicht mehrfach gelesene IDs als neue Nachrichten zählen.

Die folgenden Beispiele verwenden PowerShell im Projektverzeichnis; das im Container ausgeführte `sh` gehört zum Container. Für Datenbankabfragen wird dieser Helfer einmal in der Prüfsitzung definiert. Er benötigt keine lokale PostgreSQL-Installation:

```powershell
# Führt eine Abfrage mit den Zugangsdaten des Postgres-Containers aus.
function Invoke-DatabaseQuery([string] $query) {
    $query | docker compose exec -T postgres sh -c 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At'
    if ($LASTEXITCODE -ne 0) { throw 'Datenbankprüfung fehlgeschlagen.' }
}
```

### 19.2 S1: Build und Tests

```powershell
mvn clean test
```

Erwartung: Exitcode 0, beide Module im Reaktor, kein fehlgeschlagener Test und keine wegen fehlendem Docker übersprungenen notwendigen Integrationstests. RabbitMQ und PostgreSQL werden für Integrationstests durch Testcontainers bereitgestellt. Tests benutzen eigene Testdaten und hängen weder von einer Entwickler-`.env` noch von einem laufenden Compose-Stack ab.

Alle neuen Testklassen enden auf `Test`, einschliesslich `IntegrationTest`, damit der vorhandene Maven-Testlauf sie erfasst. Die Testberichte müssen ihre tatsächliche Ausführung ausweisen; ein grüner Lauf mit null oder unbemerkt ausgelassenen Writer-Tests erfüllt S1 nicht.

Zusätzliche verbindliche Testprüfungen:

- `BatchRuleTest`: bei 499 Einträgen und weniger als 200 ms kein Schreiben; beim 500. Eintrag sofort; nach 200 ms ein Teilstapel auch ohne weitere Lieferung; neue Einträge verlängern die Frist nicht; leerer Puffer erzeugt keinen Schreibversuch. Kontrollierte Zeit statt ungenauer Sleep-Tests.
- `MessagePersistenceIntegrationTest`: vollständige Feldübernahme, beide Queue-Formate mit und ohne Typheader, echte Transaktion und Rollback; kein ACK vor erfolgreichem Commit. Ein Fehler vor Commit darf keine teilweise bestätigten Zeilen erzeugen.
- Nachweis zum ACK-Fehler: Nach erfolgreichem Commit und vor abgeschlossener Bestätigung den Consumer-Kanal unterbrechen. Nach erneuter Zustellung bleiben die Zeilen eindeutig, die Eingangsqueue wird leer und die DLQ erhält deswegen keine Nachricht.
- `InvalidMessageIntegrationTest`: fehlerhaftes JSON, fehlendes Pflichtfeld und falscher Typ landen in der DLQ; eine danach folgende gültige Nachricht wird gespeichert. Fehlender Java-Typheader ist ausdrücklich kein Fehler.
- Die Pflichtfälle S5 und S7 haben die gesonderten Nachweise aus 19.6 und 19.8 mit echter Queue und echter Datenbank.

Gezielte Prüfung, sobald implementiert:

```powershell
mvn -pl batch-writer test '-Dtest=BatchRuleTest,MessagePersistenceIntegrationTest,InvalidMessageIntegrationTest'
```

### 19.3 S2: Frischer Stack

In einem frischen Klon ohne eigene `.env`:

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
docker compose config --format json
Invoke-DatabaseQuery '\d public.message'
Invoke-DatabaseQuery "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='message';"
```

Erwartung: vier laufende Dienste; Postgres und RabbitMQ gesund; Writer-Consumer verbunden; Schema mit exakt den sechs spezifizierten Spalten, Primary Key und Raum-/Zeitindex. Im aufgelösten Compose-Modell besitzt kein Dienst ein nicht leeres `ports`-Array. Alle vier sind an `chat-net` angeschlossen. Interne Dockerfile-`EXPOSE`-Angaben sind keine veröffentlichten Host-Ports.

### 19.4 S3: 1'000 Nachrichten über HTTP

Ein Prüflauf kann die Requests über einen temporären curl-Container im internen Netzwerk senden. Das ist kein dauerhafter Dienst. Beispiel für die Erzeugung eindeutiger Szenariodaten:

```powershell
$roomId = [guid]::NewGuid().ToString()
$requestLines = for ($number = 1; $number -le 1000; $number++) {
    @{ roomId=$roomId; senderId='review'; senderName='Review'; content="S3-$number" } | ConvertTo-Json -Compress
}
$sendScript = 'while IFS= read -r body; do curl --silent --show-error --fail-with-body --max-time 10 -H "Content-Type: application/json" --data-binary "$body" -w "\nSTATUS:%{http_code}\n" http://chat-service:8080/messages || exit 1; done'
$responses = $requestLines | docker run --rm -i --network chat-net --entrypoint sh curlimages/curl -c $sendScript
if ($LASTEXITCODE -ne 0) { throw 'Senden fehlgeschlagen.' }
$responses
Invoke-DatabaseQuery "SELECT count(*), count(DISTINCT id) FROM message WHERE room_id='$roomId';"
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

Konkrete Prüfung: exakt 1'000 `STATUS:202`-Antworten und 1'000 verschiedene IDs aus den JSON-Antworten; innerhalb von 60 Sekunden exakt diese IDs in der Datenbank, beide SQL-Zähler 1'000, Inhalte `S3-1` bis `S3-1000` vollständig, `chat.persist` leer. Zum ID-Abgleich:

```powershell
Invoke-DatabaseQuery "SELECT id FROM message WHERE room_id='$roomId' ORDER BY id;"
```

Die Ausgabe wird als Menge mit den Antwort-IDs verglichen, nicht nur nach Anzahl. Für die weiteren HTTP-Szenarien wird dieselbe Sendemethode mit neuer Raum-ID, passender Anzahl und passendem Inhaltspräfix verwendet.

### 19.5 S4: Rückstau und Transaktionszahl

```powershell
docker compose stop batch-writer
```

Danach 1'000 HTTP-Nachrichten mit neuer Raum-ID wie in 19.4 senden und alle Antwort-IDs aufzeichnen. Vor dem Start prüfen: für diese Raum-ID keine Zeilen, in `chat.persist` exakt 1'000 wartende und null unbestätigte Nachrichten sowie null Consumer. Vorherige Szenarien müssen bereits abgeschlossen sein. Nach den vorbereitenden SQL-Prüfungen zwei Sekunden warten, bevor der Ausgangswert gemessen wird, damit deren Transaktionen nicht unnötig in das Messfenster fallen.

```powershell
$before = [long](Invoke-DatabaseQuery 'SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname=current_database();')
docker compose start batch-writer
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

Während der Verarbeitung nur den Queue-Zustand pollen, keine fortlaufenden SQL-Zählabfragen. Nach leerer Queue zwei Sekunden zur Veröffentlichung der Statistik warten und den Endwert abfragen:

```powershell
$after = [long](Invoke-DatabaseQuery 'SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname=current_database();')
$transactionDelta = $after - $before
$transactionDelta
```

Konkrete Prüfung: rohe Differenz höchstens 100, alle 1'000 Antwort-IDs anschliessend in `message`, keine neue DLQ-Nachricht. Die rohe Differenz enthält auch Mess-/Nebenverkehr und ist damit eine konservative lokale Prüfung. Verbindungsinitialisierung, Healthchecks und Pool-Prüfungen müssen bei der Messung mitbeobachtet werden; nicht nur Inserts können den Datenbankzähler beeinflussen. Zwei volle Stapel wären günstig, werden aber nicht vorausgesetzt. Die protokollierten Stapelgrössen und Schreibversuche werden mit der gemessenen Differenz abgeglichen; Logs allein sind kein Transaktionsnachweis. Der anschliessende Vollständigkeits-SELECT liegt ausserhalb des Messfensters. Es gibt im Auftrag für S4 keine eigene Zeitobergrenze; ein lokaler Test bricht nach 60 Sekunden mit Diagnose ab.

Die Statistik ist zeitversetzt sichtbar. Die Wartezeit und ein ruhiger Stack gehören deshalb zur Messung; bei unstimmigen Zählern ist der Lauf nicht als bestanden zu melden. Es wird kein angenommener pauschaler Mess-Overhead abgezogen und kein Statistikzähler zwischen den Szenarien zurückgesetzt. Der endgültige Abnahmenachweis bleibt die Messung am realen Stack mit ausreichender Reserve unter 100.

### 19.6 S5: Duplikat ohne Java-Typheader

Konkreter reproduzierbarer Integrationstest:

```powershell
mvn -pl batch-writer test '-Dtest=DuplicateMessageIntegrationTest'
```

Die Prüfung muss:

1. Eine gültige Nachricht mit neuer ID erzeugen und ihren UTF-8-JSON-Body zweimal unverändert per AMQP auf den Standard-Exchange mit Routing-Key `chat.persist` veröffentlichen.
2. Als einzige gesetzte AMQP-Eigenschaft `content_type=application/json` verwenden, insbesondere kein `__TypeId__` und kein vom Java-Konverter erzeugtes Objekt senden.
3. Auf abgeschlossene Verarbeitung warten und für die ID exakt eine unveränderte Zeile sowie eine leere Eingangsqueue feststellen.
4. Prüfen, dass keine neue Lieferung in `chat.dlq` liegt.
5. Zusätzlich denselben Fall innerhalb eines Stapels, in getrennten Stapeln nach bereits erfolgtem Commit und zusammen mit einer neuen gültigen ID abdecken. Auch bei null neu eingefügten Zeilen werden die Duplikat-Lieferungen bestätigt. Die gemischte Variante speichert die neue ID und verwirft nicht den gesamten Stapel.

Die identische Prüfung im Compose-Stack verwendet dieselben AMQP-Parameter und folgende SQL-Abfrage mit der eingespeisten ID:

```powershell
$messageId = 'c8c8b460-3bbb-4f73-9b8b-c8a7eca89125' # Für jeden echten Prüflauf eine neue UUID verwenden.
Invoke-DatabaseQuery "SELECT count(*) FROM message WHERE id='$messageId';"
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

Erwartung: Zähler 1, Eingangsqueue vollständig leer und kein DLQ-Zuwachs. Da S1 bis S4 im Bewertungsdurchlauf keine DLQ-Nachrichten erzeugen dürfen, muss `chat.dlq` hier zusätzlich absolut leer sein. Ein bereits vorhandener unerwarteter DLQ-Bestand ist ein Fehler des vorangegangenen Durchlaufs und darf nicht durch blosses Betrachten der Differenz verdeckt werden. Nicht zweimal HTTP verwenden: Das würde zwei neue IDs erzeugen und S5 nicht prüfen.

### 19.7 S6: Zwei Instanzen

```powershell
docker compose up -d --scale batch-writer=2
docker compose ps batch-writer
docker compose exec rabbitmq rabbitmqctl list_queues name consumers messages_ready messages_unacknowledged
docker compose logs --no-color batch-writer
```

Vor dem Senden müssen genau zwei Writer-Container laufen und `chat.persist` zwei Consumer besitzen. Anschliessend 1'000 Nachrichten mit neuer Raum-ID wie in 19.4 senden. Prüfen: alle Antwort-IDs gespeichert, `count(*)=count(DISTINCT id)=1000`, Eingangsqueue leer, kein DLQ-Zuwachs. Beide Instanzen bleiben als Consumer verbunden; die Logs erlauben die Zuordnung verarbeiteter Stapel. Eine exakt gleiche Nachrichtenanzahl pro Instanz ist kein Abnahmekriterium. Lokale Prüfgrenze: 60 Sekunden nach letzter HTTP-Annahme.

Zusätzlicher Integrationsnachweis für die behauptete Konkurrenzsicherheit: Zwei getrennte Writer-Verbindungen verarbeiten dieselbe Nachrichten-ID über zeitlich überlappende Transaktionen. Nach Abschluss existiert genau eine Zeile, beide Lieferungen sind bestätigt und die DLQ bleibt leer. Dieser gezielt synchronisierte Test prüft mehr als der offizielle S6-Lauf mit 1'000 unterschiedlichen IDs; ein zufälliger Lauf mit zwei Instanzen allein beweist den Konfliktfall nicht.

### 19.8 S7: PostgreSQL-Ausfall und automatische Erholung

Verbindlicher Integrationstest:

```powershell
mvn -pl batch-writer test '-Dtest=DatabaseOutageIntegrationTest'
```

Konkreter Testablauf: Beide Writer aus S6 laufen weiter und ihre Datenbankverbindungen wurden zuvor erfolgreich verwendet. PostgreSQL stoppen, den abgeschlossenen Stop als Zeitpunkt null festhalten, unmittelbar 300 gültige Nachrichten einspeisen und deren IDs festhalten; unabhängig vom Senden nach 15 Sekunden denselben PostgreSQL-Container mit unverändertem Datenvolume starten. Writer und RabbitMQ werden nicht neu gestartet. Die 300 HTTP-Anfragen müssen während des Ausfalls abgesendet und ihre Antworten aufgezeichnet werden; ein Lauf, der einen wesentlichen Teil erst nach Wiederherstellung sendet, prüft den geforderten Ausfall nicht ausreichend. Der Integrationstest bildet denselben Fehler mit echter Queue, echter Datenbank und zwei Consumer-Instanzen nach. Der Test muss den Ausfall bereits verwendeter Datenbankverbindungen abdecken, nicht nur einen erstmaligen Verbindungsaufbau.

Entsprechende Compose-Steuerbefehle:

```powershell
docker compose stop postgres
# Jetzt 300 HTTP-Nachrichten senden; PostgreSQL-Wiederstart zeitgesteuert parallel auslösen.
docker compose start postgres
docker compose ps batch-writer
docker compose logs --no-color --since 2m batch-writer
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers
```

Konkrete Messung: Eine unabhängige Stoppuhr startet bei Zeitpunkt null. Innerhalb von 90 Sekunden muss die Vereinigung der auf den Testlauf gefilterten Datenbank-IDs und der per nicht entfernender DLQ-Abfrage gelesenen IDs exakt den 300 Test-IDs entsprechen. Keine dieser IDs bleibt in `chat.persist`. SQL zählt jede ID höchstens einmal. DLQ-Abfrage über die interne Management-API: `POST http://rabbitmq:15672/api/queues/%2F/chat.dlq/get`, authentifiziert mit den RabbitMQ-Zugangsdaten, JSON-Body `{"count":10000,"ackmode":"ack_requeue_true","encoding":"auto","truncate":1000000}`. Der vor S7 bekannte DLQ-Bestand und die Test-IDs werden getrennt betrachtet; Bodies dürfen für den ID-Nachweis nicht abgeschnitten sein. Bei grösserem Bestand ist die Abrufgrenze passend zu erhöhen.

Zusätzlich nach Wiederherstellung und nach vollständiger Behandlung der 300 Testnachrichten weitere gültige Kontrollnachrichten senden. Innerhalb von zehn Sekunden nach deren letzter HTTP-Annahme müssen sie in PostgreSQL ankommen. Pro Writer ist mindestens ein neuer erfolgreicher Commit nach Wiederherstellung anhand seiner Logs nachzuweisen; falls die Queue zunächst nur einen Consumer bedient, werden weitere kleine Kontrollgruppen gesendet, bis beide nachweislich gearbeitet haben, insgesamt höchstens 30 Sekunden lang. Container-ID, Startzeit und Restart-Zähler beider Writer dürfen sich während des Tests nicht ändern. Damit ist nachgewiesen, dass nicht bloss eine Restart-Policy einen abgestürzten Writer ersetzt hat oder eine gesunde Instanz den dauerhaften Ausfall der anderen verdeckt. Diese zusätzliche Erholungsprüfung liegt ausserhalb der 90-Sekunden-Frist für die ursprünglichen 300 Nachrichten und ersetzt sie nicht.

### 19.9 S8: Quelltext und Geheimnisse

```powershell
git ls-files -- .env
git check-ignore -v .env
rg -n '\.stream\(|\.parallelStream\(|\bStream\b|java\.util\.stream' batch-writer/src
```

Erwartung: `.env` ist nicht getrackt und wird ignoriert; keine Stream-Verwendung. Zusätzlich jede Klasse, jeden Record, Konstruktor und jede Methode im Writer samt Tests durchsehen: erklärender deutscher Kommentar vorhanden, englische Bezeichner und Logs, kurze Methoden, benannte Zwischenresultate, keine unnötigen Interfaces oder Abstraktionen. Das gilt auch für `main`, Bean-Methoden, private Helfer, Testmethoden sowie selbst geschriebene verschachtelte oder anonyme Klassen. Von Lombok bzw. Java erzeugte Methoden benötigen keinen eigenen Quelltextkommentar. Die strengere Vorgabe „keine Streams“ folgt ausdrücklich aus S8, auch wenn CLAUDE.md Schleifen als bevorzugten Weg beschreibt. Ein Kommentar erklärt Zweck oder Fehlervermeidung und wiederholt nicht nur den Methodennamen. Die Textsuche allein beweist die Stilregeln nicht; die manuelle Prüfung ist Bestandteil der Abnahme.

## 20. Zuordnung der Szenarien S1–S8

| Szenario aus dem Bewertungsauftrag | Verbindliches Ergebnis | Technische Konsequenz | Nachweis |
|---|---|---|---|
| S1: `mvn clean test` in der Wurzel | Ein Lauf, alles grün | Maven-Modul integrieren, unabhängige Unit- und echte Integrationstests | 19.2 |
| S2: Frischer Klon, `.env.example`, Compose-Build | Alle Dienste laufen; kein veröffentlichter Port | Vollständige Konfiguration, Dockerfiles, initiales Schema, `chat-net` | 19.3 |
| S3: 1'000 Nachrichten über `POST /messages` | Binnen 60 Sekunden alle gespeichert, Queue leer | JSON-Vertrag, Teilstapel-Timeout, Commit vor ACK | 19.4 |
| S4: 1'000 aufgestaute Nachrichten, dann Writer-Start | Kein Verlust, höchstens 100 DB-Transaktionen | Echte Stapel in gemeinsamen Transaktionen, kein Auto-Commit pro Zeile | 19.5 |
| S5: Gleicher JSON-Body zweimal, nur `content_type` | Eine Zeile, kein DLQ-Zuwachs | Expliziter Zieltyp, stabile ID, `ON CONFLICT (id) DO NOTHING` | 19.6 |
| S6: Zwei Writer, 1'000 Nachrichten | Zwei Consumer, alles vorhanden, keine doppelten Zeilen | Competing Consumers, lokale Puffer, DB-Primary-Key, kein fester Containername | 19.7 |
| S7: DB 15 Sekunden gestoppt, 300 Nachrichten | Binnen 90 Sekunden in DB oder spezifizierter DLQ; Writer ohne manuellen Neustart | Drei begrenzte Schreibversuche, Fehlerziel DLQ, automatische neue Verbindungen | 19.8 |
| S8: Writer-Quelltext und `.env` | CLAUDE.md erfüllt; keine Streams; Kommentare über jeder Klasse/Methode; keine `.env` im Repo | Einfacher dokumentierter Code, Git-Ignore für lokale Geheimnisse | 19.9 |

Ausserhalb der acht Szenarien verlangt der Auftrag vier Abgaben: diese Spezifikation, später `docs/plan-batch-writer.md`, die Implementierung und Tests unter `batch-writer/src/test/`. Der spätere Plan muss vor der Implementierung entstehen und mit kleinen, getesteten Commits in der Git-Historie übereinstimmen. Die README-Standtabelle ist erst bei der späteren Umsetzung nachzuführen. Dieses Dokument allein behauptet keine bestandene Abnahme.

### Kritische Prüfung gegen S1–S8 – Schritt 2

„Reicht das?“ beurteilt die Spezifikation nach der Überarbeitung, nicht eine bereits vorhandene Implementierung. Kein Szenario ist damit als bestanden nachgewiesen.

| Szenario | Was verlangt wird | Stelle in unserer Spezifikation | Reicht das? | Risiko |
|---|---|---|---|---|
| S1 | `mvn clean test` in der Wurzel, alles in einem Lauf grün | 1, 17, 19.2 | Als Vorgabe ja; tatsächliche Testausführung ausdrücklich gefordert | Neue Integrationstests werden falsch benannt, übersprungen oder hängen an einer lokalen Umgebung |
| S2 | Frischer Klon und `.env.example`, alle Dienste laufen, keine Host-Ports | 3, 15–17, 19.3 | Als Vorgabe ja | Unvollständiger Maven-Docker-Build, Schema noch nicht bereit, altes Volume verdeckt eine fehlerhafte Erstinitialisierung |
| S3 | 1'000 HTTP-Nachrichten binnen 60 Sekunden gespeichert, Queue leer | 4–8, 19.1, 19.4 | Als Vorgabe ja, nach Präzisierung des Empfangsfortschritts | Blockierter Listener, nicht ausgelöster Teilstapel-Timer oder voreilige Erfolgsmeldung bei noch offenen ACKs |
| S4 | 1'000 wartende Nachrichten ohne Verlust mit höchstens 100 DB-Transaktionen schreiben | 5–7, 19.5 | Messbar spezifiziert; Einhaltung muss am echten Stack nachgewiesen werden | Prefetch wird mit Batch-Grösse verwechselt; Auto-Commit oder Mess-/Verbindungsverkehr erhöht die Transaktionszahl |
| S5 | Derselbe JSON-Body zweimal, nur `content_type`: eine Zeile und nichts in der DLQ | 3, 8–9, 14, 19.6 | Als Vorgabe ja; Null-Update-Zähler und absolute DLQ-Leere ergänzt | Typheader-Abhängigkeit, Duplikat wird als Schreibfehler gewertet oder ACK-Fehler löst fälschlich DLQ aus |
| S6 | Zwei angeschlossene Writer, alle 1'000 Nachrichten vorhanden, keine Duplikate | 5, 12, 14, 17, 19.7 | Als Vorgabe ja; zusätzlicher Konflikttest gefordert | Gemeinsam genutzter Kanal/Puffer, feste Containernamen oder ungetestete konkurrierende Inserts |
| S7 | PostgreSQL 15 Sekunden weg; 300 Nachrichten binnen 90 Sekunden in DB oder festgelegtem Fehlerziel; Writer erholt sich | 8, 10–12, 19.8 | DLQ ist laut Auftrag zulässig; Zeitgrenzen und Erholung beider Instanzen bleiben nachzuweisen | Drei Versuche können vor Wiederkehr erschöpft sein; 25 Sekunden pro Stapel beweisen nicht die Gesamtfrist; defekte Verbindungen oder eine dauerhaft ausgefallene Instanz |
| S8 | Keine Streams, Kommentare über jeder Klasse/Methode, `.env` nicht im Repo | Grundlagen, 16, 18, 19.9 | Als Vorgabe ja; Prüfbereich ausdrücklich präzisiert | Nur Hauptklassen werden kommentiert, Helfer/Tests fehlen; Textsuche wird mit vollständiger Stilprüfung verwechselt |
