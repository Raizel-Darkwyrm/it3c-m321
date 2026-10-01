# Umsetzungsplan: batch-writer – Bewertung 1

Stand: 01.10.2026. Grundlage ist die verbindliche [Spezifikation](spec-batch-writer.md), eingecheckt mit `dde7c4c`. Dieser Plan enthält noch keine Implementierung und keine Behauptung über bestandene Tests.

Als Vorbild dient [plan-chat-service.md](plan-chat-service.md): kleine Aufgaben, zuerst ein überprüfbarer Test, dann Umsetzung, Prüfung und ein thematisch begrenzter Commit. Die dortigen Java-Beispiele werden nicht kopiert. Massgeblich bleiben [CLAUDE.md](../CLAUDE.md) und die Spezifikation, insbesondere ihre 16 Entscheidungen und S1–S8. Es werden keine weiteren Anwendungsfunktionen oder Dienste eingeführt.

## Arbeitsweise und Git-Reihenfolge

- Dieser Plan wird vor Beginn der Implementierung separat gesichert. Vorgeschlagene Commit-Message: `docs: Umsetzungsplan für batch-writer festlegen`. Das Erstellen dieses Dokuments führt selbst keinen Commit aus.
- Danach Aufgaben 01 bis 21 in der angegebenen Reihenfolge bearbeiten. Die Nummern beziehen sich auf diesen Plan, nicht auf die bisherigen Gesprächsschritte.
- Pro Aufgabe zuerst den angegebenen Test schreiben oder die Prüfung ausführen. Bei einer neuen Funktion den erwarteten fachlichen Fehlschlag festhalten; ein nicht verfügbares Docker oder ein Downloadfehler ist kein fachlicher Rot-Nachweis.
- Nach der Umsetzung den gezielten Test und die bis dahin vorhandenen Writer-Tests ausführen. Änderungen am Eltern-POM oder am gemeinsamen Build zusätzlich mit dem Root-Testlauf prüfen. Keine relevanten Tests überspringen, um einen grünen Commit zu erhalten.
- Jede Aufgabe ergibt einen Commit mit Implementierung und zugehörigen Tests bzw. Prüfdokumentation zum selben Thema. Keine künstlichen leeren Commits. Ein Thema bleibt auch dann eines, wenn Quelltext, Test und Konfiguration dafür gemeinsam geändert werden müssen.
- Bei reinen Nachweisen kann die Ausgangsprüfung schon grün sein. Dann wird kein Fehlschlag erfunden: der neue Test oder ein ehrliches Prüfprotokoll ist das Ergebnis.
- Prüfen und committen nur die zur Aufgabe gehörenden Pfade. Kein pauschales `git add .`. `.env` und der private Ordner `Erklärungen` bleiben ausgeschlossen.
- Im Plan erst nach tatsächlichem Abschluss ein Häkchen setzen; diese Statusänderung gehört in den jeweiligen Aufgaben-Commit. Keine nachträgliche Umordnung, um eine abweichende Git-Historie zu verdecken. Notwendige Plananpassungen vor ihrer Umsetzung begründen und separat dokumentieren.
- Vorgeschlagene Commit-Messages sind deutsch, Code-Bezeichner und Logs englisch. Jede geschriebene Klasse, jeder Record, Konstruktor und jede Methode einschliesslich Tests bekommt einen deutschen erklärenden Kommentar. Keine Streams, keine Vorrats-Interfaces.
- Zwischenstände sind noch kein abnahmefähiger Gesamtstack. Bis Aufgabe 12 gibt es bewusst keinen laufenden produktiven Queue-Consumer. Bis Aufgabe 18 gibt es keinen Writer-Container im Compose-Stack.
- Tests verwenden eigene RabbitMQ-/PostgreSQL-Testcontainer. Diese dürfen für unabhängige Tests isoliert werden. Der abschliessende Compose-Durchlauf S2–S8 verwendet dagegen denselben Stack ohne Aufräumen und übernimmt die zwei Instanzen aus S6 nach S7.

## Offene Prüfungen zum Nachholen

Stand 01.10.2026: **BLOCKIERT – Docker-/Compose-Umgebung fehlt.**
Diese Liste betrifft bereits bearbeitete Aufgaben; Tests späterer Aufgaben
sind weiterhin dort geplant. Kein Test wird wegen dieser Markierung deaktiviert.
Erst nach erfolgreicher Ausführung mit Datum und Ergebnis abhaken.

Voraussetzungen: `docker version` muss Client und Server erreichen;
`docker compose version` muss funktionieren. Maven benötigt Java 21.
Alle Befehle beginnen in der Projektwurzel. Compose-Prüfungen mit der
Beispielvorlage in einem frischen Testklon mit leerem Testvolume ausführen;
kein bestehendes Benutzer-Volume löschen oder eine vorhandene `.env` überschreiben.

- [ ] **N1 – Aufgabe 01: Root-Testlauf wiederholen.** `mvn clean test`.
  Bisher wegen fehlender Docker-Umgebung fehlgeschlagen, insbesondere die
  chat-service-Klassen `RabbitConfigIntegrationTest`,
  `MessagePublisherIntegrationTest` und `MessageControllerIntegrationTest`.
  Erwartung: alle bis dahin vorhandenen Modul- und Integrationstests erfolgreich,
  keine wegen fehlender Infrastruktur übersprungenen Tests. Der mittlerweile
  hinzugekommene Schematest muss ebenfalls erfolgreich sein.
- [ ] **N2 – Aufgabe 01: chat-service-Image bauen.**
  `docker compose --env-file .env.example build chat-service`.
  Bisher nicht ausführbar, weil der Docker-Befehl fehlt.
  Erwartung: erfolgreicher Build trotz zusätzlichem Maven-Modul.
- [ ] **N3 – Aufgabe 03: Schema gegen PostgreSQL 16 prüfen.**
  `mvn -pl batch-writer test '-Dtest=SchemaIntegrationTest'`.
  Bisher Setup-Fehler beim Containerstart; die fünf Prüfmethoden wurden nicht erreicht.
  Erwartung: alle fünf erfolgreich (Spalten/Typen/NOT NULL, keine Raumtabelle/FKs,
  Primary Key, B-Tree-Index, gültige Inserts und Ablehnung doppelter IDs).
- [ ] **N4 – Aufgabe 03: gesamten Writer-Testlauf wiederholen.**
  `mvn -pl batch-writer clean test`.
  Nach Aufgabe 07: 46 erfolgreiche Prüfungen und fünf Docker-Setup-Fehler:
  je einer in `SchemaIntegrationTest` und `BatchWriterApplicationTest` sowie drei
  in `RabbitConfigIntegrationTest`. Erwartung: gesamter Lauf erfolgreich,
  einschliesslich Schema, Verbindungen und Queue-Deklaration.
- [ ] **N5 – Aufgabe 04: Compose-Modell validieren.**
  `docker compose --env-file .env.example config --format json`.
  Bisher nicht ausführbar. Prüfen: `postgres:16`, drei gesetzte Postgres-Variablen,
  `chat-net`, `postgres-data`, schreibgeschützter SQL-Mount, TCP-Healthcheck und
  keine veröffentlichten Host-Ports.
- [ ] **N6 – Aufgabe 04: Erststart und Schema prüfen.**
  `docker compose --env-file .env.example up -d postgres`, danach
  `docker compose --env-file .env.example ps postgres`.
  Bisher nicht ausgeführt. Erwartung: gesund; anschliessend mit
  `docker compose --env-file .env.example exec postgres psql -U chat -d chat -c '\d public.message'`
  genau die sechs Spalten, den Primary Key und `message_room_sent_at_idx` prüfen.
  Die angegebenen Zugangsdaten gelten für die unveränderte Beispielvorlage.
- [ ] **N7 – Aufgabe 04: Datenerhalt beim Neustart prüfen.**
  Im isolierten Teststack eine Zeile mit bekannter neuer ID und vollständigen
  Werten einfügen, danach `docker compose --env-file .env.example restart postgres`.
  Nach erneuter Bereitschaft per SELECT anhand dieser ID prüfen: exakt eine
  Zeile mit unveränderten Werten. Bisher nicht ausgeführt.

- [ ] **N8 – Aufgabe 05: Anwendungsstart und echte Verbindungen prüfen.**
  `mvn -pl batch-writer test '-Dtest=ApplicationConfigurationTest,BatchWriterApplicationTest'`.
  Vier Konfigurationsprüfungen sind erfolgreich. Die vier Methoden des erweiterten
  Anwendungsstarttests werden wegen fehlender Docker-Umgebung nicht erreicht.
  Erwartung: Spring-Kontext ohne Webserver, echte JDBC-Abfrage ohne Schema-Erstellung,
  erfolgreiche AMQP-Verbindung und keine registrierten Consumer. Die Tests verwenden
  eigene Container-Zugangsdaten statt lokaler `.env`-Werte.

- [ ] **N9 – Aufgabe 06: RabbitMQ-Topologie am echten Broker prüfen.**
  `mvn -pl batch-writer test '-Dtest=RabbitConfigIntegrationTest'`.
  Drei Prüfungen wegen fehlendem Docker bereits beim Containerstart blockiert.
  Erwartung: beide Queues direkt nach Kontextstart vorhanden, wiederholte
  Deklaration mit den Producer-Eigenschaften erfolgreich, widersprüchliche
  Eigenschaften abgelehnt und vorhandene Queue erhalten. Keine Consumer.

Nach dem Nachholen die Ergebnisse auch in den Prüfständen der Aufgaben 01,
03, 04, 05 und 06 eintragen. Deren Abschlusskästchen bleiben bis dahin offen.

## Dateinamen und Testkonventionen

Zur Lesbarkeit stehen in den Aufgaben folgende Pfadkürzel:

- `main/` = `batch-writer/src/main/java/ch/benedict/m321/batchwriter/`
- `test/` = `batch-writer/src/test/java/ch/benedict/m321/batchwriter/`
- `resources/` = `batch-writer/src/main/resources/`

Alle genannten Klassen und Dateien sind geplante Dateien, sofern sie nicht bereits im Repository existieren. Die Aufteilung benennt konkrete Verantwortlichkeiten, keine zusätzlichen Dienste. Helfer werden erst mit ihrem ersten tatsächlichen Bedarf angelegt. Ein gemeinsam benötigter Container-Testaufbau darf dann unter `test/support/` entstehen; keine allgemeine Testplattform auf Vorrat.

Testklassen enden auf `Test`, auch `IntegrationTest`, damit `mvn test` sie wie im vorhandenen Projekt erfasst. Gezielte Befehle gelten nach Anlage des Moduls; ein fehlendes Modul vor Aufgabe 01 ist erwartbar. Die Abnahmekriterien und Messgrenzen werden aus Spezifikation Kapitel 19 übernommen und hier nicht abgeschwächt.

## Aufgabe 01: Maven-Modul und Anwendungsstart

- [ ] Abgeschlossen und geprüft

Prüfstand 01.10.2026: Modul und Startklasse implementiert; die zwei Prüfungen in
`BatchWriterApplicationTest` sind grün. Der erste Testlauf ohne Startklasse scheiterte
erwartungsgemäss beim Übersetzen. Die Root-Ausgangsprüfung und der erneute Root-Lauf
scheitern an drei vorhandenen chat-service-Integrationstests, weil Testcontainers
keine Docker-Umgebung findet. `docker compose build chat-service` ist ebenfalls
blockiert: Der Docker-Befehl ist nicht verfügbar. Keine Tests wurden deaktiviert.
Das Abschlusskästchen bleibt bis zum erfolgreichen Root-Lauf und Docker-Build offen.

1. **Ziel:** Ein baubares Modul `batch-writer` im vorhandenen Maven-Reaktor.
2. **Warum kommt dieser Schritt jetzt?** Alle weiteren Tests brauchen einen ausführbaren Modulrahmen.
3. **Welche Dateien werden verändert?** Root-`pom.xml`; neu `batch-writer/pom.xml`, `main/BatchWriterApplication.java`, `test/BatchWriterApplicationTest.java`; `chat-service/Dockerfile` für die zusätzliche Modul-POM im Reaktor.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** Zuerst `mvn clean test` als Ausgangsprüfung; dann `BatchWriterApplicationTest` für einen Spring-Kontext ohne Webserver. Gezielt: `mvn -pl batch-writer test -Dtest=BatchWriterApplicationTest`.
5. **Was erwarten wir vor der Implementierung?** Bestehende Tests sind überprüfbar; das neue Modul bzw. seine Startklasse fehlt. Der neue Test kann deshalb noch nicht erfolgreich ausgeführt werden.
6. **Was implementieren wir?** Modul mit geerbtem Java 21/Spring Boot 3.5.16, zunächst minimalen Start-/Testabhängigkeiten, Lombok und Boot-Packaging. Kein Web-Starter. Die bisherige Docker-Buildstruktur muss trotz neuer Modulliste weiterhin alle nötigen POMs finden.
7. **Was erwarten wir danach?** Kontext-Test und Root-Testlauf grün; `docker compose build chat-service` bleibt möglich. Noch keine Datenbank- oder Broker-Verarbeitung.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S1, S2 und S8 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `chore: Maven-Modul für batch-writer anlegen`

## Aufgabe 02: JSON-Vertrag und Eingabeprüfung

- [x] Abgeschlossen und geprüft

Prüfstand 01.10.2026: Eigener Record und Decoder implementiert. Der erste
Vertragstest scheiterte erwartungsgemäss an den noch fehlenden Klassen.
Nach Umsetzung und ergänzten Grenzprüfungen ist `mvn -pl batch-writer clean test`
erfolgreich: 34 Decoder-Prüfungen und zwei vorhandene Kontextprüfungen,
keine Fehler und keine übersprungenen Tests. Kein Broker-/Datenbankzugriff;
die offenen Docker-Prüfungen aus Aufgabe 01 bleiben davon unberührt.

1. **Ziel:** Vollständige Nachrichten aus UTF-8-JSON ohne Java-Typheader lesen und prüfen.
2. **Warum kommt dieser Schritt jetzt?** Puffer und Datenbank sollen bereits mit dem richtigen Datenmodell arbeiten.
3. **Welche Dateien werden verändert?** `batch-writer/pom.xml`; neu `main/dto/ChatMessage.java`, `main/messaging/MessageDecoder.java`, `test/messaging/MessageDecoderTest.java`.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=MessageDecoderTest`: gültiger Body mit einzigem Metadatum `content_type`, alle sechs Felder, zusätzliche Felder, fehlende Werte, falsche Typen, ungültiges JSON/UTF-8 und doppelte Feldnamen.
5. **Was erwarten wir vor der Implementierung?** Record und Decoder fehlen; die Vertragsprüfungen sind noch nicht erfüllt.
6. **Was implementieren wir?** Eigenen Record, explizite Deserialisierung und Validierung gemäss Spezifikation 3–4. Keine Typheader-Abhängigkeit, keine neue ID, kein Trimmen, keine Raum-/Benutzerprüfung. Fehler werden an den späteren Consumer gemeldet; noch kein Brokerzugriff.
7. **Was erwarten wir danach?** Gültige Nachrichten behalten ihre Daten, ungültige werden eindeutig erkannt. Noch keine DLQ-Übergabe; diese ist später Aufgabe des Consumers.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S3, S5 und S8 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: JSON-Vertrag des batch-writer prüfen`

## Aufgabe 03: PostgreSQL-Schema mit echtem Datenbanktest

- [ ] Abgeschlossen und geprüft

Prüfstand 01.10.2026: Init-Skript und Schematest implementiert. Der erste Lauf
meldete erwartungsgemäss die fehlende Init-Datei. Nach deren Anlage findet der
Test die Datei, kann aber mangels Docker-Umgebung PostgreSQL nicht starten.
`mvn -pl batch-writer clean test`: 36 vorhandene Prüfungen erfolgreich,
ein Setup-Fehler im Schematest, keine übersprungenen Tests. Die fünf Prüfmethoden
des Schematests konnten noch nicht ausgeführt werden. Das Abschlusskästchen
bleibt bis zur erfolgreichen Prüfung mit PostgreSQL 16 offen.

1. **Ziel:** Die Tabelle `message` mit sechs Spalten, Primary Key und Raum-/Zeitindex reproduzierbar erstellen.
2. **Warum kommt dieser Schritt jetzt?** Das Schema muss vor seiner Einbindung in einen frischen Compose-Stack vorliegen; sonst würde ein zunächst leeres Volume ohne Tabelle initialisiert.
3. **Welche Dateien werden verändert?** Neu `postgres/init/001-create-message.sql`, `test/database/SchemaIntegrationTest.java`; `batch-writer/pom.xml` für PostgreSQL-Testcontainers und erforderliche Testabhängigkeiten.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=SchemaIntegrationTest`: PostgreSQL 16 mit genau der geplanten Init-Datei starten; Spalten, Typen, NOT NULL, Primary Key, Index und fehlenden Raum-Fremdschlüssel prüfen.
5. **Was erwarten wir vor der Implementierung?** Init-Datei/Tabelle fehlen; die Schema-Abnahme schlägt fehl.
6. **Was implementieren wir?** Ausschliesslich Tabelle und Index nach Spezifikation 13–15. Test liest dasselbe SQL-Artefakt wie später Compose, keine abweichende Testkopie.
7. **Was erwarten wir danach?** Schema-Test grün; beliebige gültige Raum-UUID ist ohne Stammdaten zulässig; doppelte IDs verletzt der Primary Key. Keine Room-/Member-Tabellen.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S2–S7 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: Nachrichtenschema für PostgreSQL anlegen`

## Aufgabe 04: PostgreSQL und Zugangsdaten im Compose-Stack

- [ ] Abgeschlossen und geprüft

Prüfstand 01.10.2026: Postgres-Dienst, Volume, schreibgeschützter Init-Mount,
TCP-Healthcheck und drei Beispielvariablen ergänzt. `docker compose config
--format json` ist vor und nach der Änderung nicht ausführbar, weil der
Docker-Befehl fehlt. Start, Schema und Datenerhalt beim Neustart sind daher
noch nicht geprüft. Die lokale `.env` blieb unverändert und ist weiterhin
ignoriert und nicht getrackt. Das Abschlusskästchen bleibt offen.

1. **Ziel:** PostgreSQL mit dem geprüften Schema intern starten.
2. **Warum kommt dieser Schritt jetzt?** Das Init-Skript existiert; der erste Containerstart kann deshalb das vollständige Schema erzeugen.
3. **Welche Dateien werden verändert?** `docker-compose.yml`, `.env.example`; lokale `.env` nur bei Bedarf ergänzen, niemals committen.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `docker compose config --format json`: Dienst `postgres`, drei Postgres-Variablen, Netzwerk, Volume und Init-Mount prüfen; danach Start-/Schema-Prüfungen aus Spezifikation 19.3.
5. **Was erwarten wir vor der Implementierung?** Der Postgres-Dienst fehlt. Ein Start mit `docker compose up -d postgres` kann dieses Ziel noch nicht erfüllen.
6. **Was implementieren wir?** `postgres:16`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, Volume `postgres-data`, schreibgeschützter Init-Mount, TCP-Bereitschaftsprüfung und `chat-net`; keine Host-Ports. Noch kein Writer-Service.
7. **Was erwarten wir danach?** PostgreSQL startet mit `.env.example` als Vorlage und leerem Test-Datenverzeichnis; Tabelle und Index vorhanden, Neustart erhält Daten. Ein frischer Klon/Teststack wird benutzt, kein bestehendes Benutzer-Volume gelöscht.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S2 und S8 vorbereitet; S2 insgesamt noch nicht erfüllt.
9. **Vorgeschlagene deutsche Commit-Message:** `chore: PostgreSQL im internen Compose-Netz ergänzen`

## Aufgabe 05: Anwendungskonfiguration für Datenbank und Broker

- [ ] Abgeschlossen und geprüft

Prüfstand 01.10.2026: JDBC-/AMQP-Abhängigkeiten, Anwendungskonfiguration und Tests
implementiert. Der Ausgangstest scheiterte an der fehlenden `application.yml`.
Nach Umsetzung sind alle vier Konfigurationsprüfungen erfolgreich. Der vollständige
Writer-Lauf meldet 38 erfolgreiche Prüfungen und zwei Docker-Setup-Fehler, keine
übersprungenen Tests. Die bisherigen zwei Kontextprüfungen benötigen durch die
geplante Erweiterung jetzt ebenfalls Testcontainer; zusammen mit den zwei neuen
Verbindungsprüfungen konnten sie noch nicht ausgeführt werden. Nachholen unter N8;
das Abschlusskästchen bleibt offen. Noch keine Topologie, Consumer oder DB-Retries.

1. **Ziel:** Der Writer kann mit den spezifizierten Variablen auf echte Infrastruktur zugreifen.
2. **Warum kommt dieser Schritt jetzt?** Schema und externe Konfiguration sind festgelegt; nun wird die Java-Anwendung daran angebunden.
3. **Welche Dateien werden verändert?** `batch-writer/pom.xml`, neu `resources/application.yml`, `test/config/ApplicationConfigurationTest.java`; vorhandenen `BatchWriterApplicationTest` auf benötigte Testcontainer-Verbindungen erweitern.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=ApplicationConfigurationTest,BatchWriterApplicationTest`: Variablen auflösen, JDBC-Verbindung und AMQP-Verbindungsdaten prüfen, keinen HTTP-Webserver starten.
5. **Was erwarten wir vor der Implementierung?** Anwendung startet bisher nur minimal; Datenbank-/AMQP-Konfiguration fehlt.
6. **Was implementieren wir?** JDBC-, PostgreSQL- und AMQP-Abhängigkeiten, Hosts/Ports/Vhost und Zugangsdaten gemäss Spezifikation 16; kein automatisches Schemaerzeugen durch den Writer. Tests liefern ihre Verbindungen über Testkonfiguration, unabhängig von lokaler `.env`.
7. **Was erwarten wir danach?** Konfigurationstests und bisherige Modultests grün; benötigte Verbindungen stehen bereit, es wird noch nichts konsumiert. Die vollständige Versuchsdauer wird erst in Aufgabe 10 abgesichert.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S1, S2, S7, S8 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: Datenbank und Broker für batch-writer konfigurieren`

## Aufgabe 06: RabbitMQ-Topologie kompatibel deklarieren

- [ ] Abgeschlossen und geprüft

Prüfstand 01.10.2026: Queue-Namen, Queue-Beans und explizite RabbitAdmin-Initialisierung
beim Start implementiert. Der Test wurde zuerst geschrieben; nach Korrektur eines
Test-Rückgabetyps scheiterte er erwartungsgemäss nur an der fehlenden RabbitConfig.
Der anschliessende vollständige Writer-Lauf kompiliert erfolgreich: 38 Prüfungen
grün, fünf Docker-Setup-Fehler, keine übersprungenen Tests. Die drei neuen
Broker-Prüfungen konnten noch nicht ausgeführt werden; Nachholmarkierung N9.
Die Eigenschaften wurden mit dem vorhandenen chat-service-Quelltext verglichen.
Das Abschlusskästchen bleibt bis zum erfolgreichen Broker-Test offen.

1. **Ziel:** `chat.persist` und `chat.dlq` unabhängig vom ersten HTTP-Aufruf bereitstellen.
2. **Warum kommt dieser Schritt jetzt?** Der Verbindungsaufbau ist geprüft; Topologiefehler werden vor Einführung des Consumers isoliert sichtbar.
3. **Welche Dateien werden verändert?** Neu `main/config/QueueNames.java`, `main/config/RabbitConfig.java`, `test/config/RabbitConfigIntegrationTest.java`; bei Bedarf Testcontainer-Abhängigkeiten im Modul-POM ergänzen.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=RabbitConfigIntegrationTest`: echter RabbitMQ 3.13, Eigenschaften und Dead-Letter-Argumente prüfen, Deklaration wiederholen und mit der Producer-Topologie vergleichen.
5. **Was erwarten wir vor der Implementierung?** Writer deklariert die Queues noch nicht; ein frischer Testbroker enthält sie nicht.
6. **Was implementieren wir?** Identische dauerhafte, nicht exklusive Queues mit Standard-Dead-Letter-Exchange und Routing-Key `chat.dlq`. Keine neue Queue, kein Consumer für `chat.delivery`, keine automatische Löschung bestehender Queues.
7. **Was erwarten wir danach?** Wiederholte kompatible Deklarationen erfolgreich; falsche Eigenschaften werden nicht durch Löschen versteckt.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S2, S5–S7 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: RabbitMQ-Queues für batch-writer bereitstellen`

## Aufgabe 07: Batch-Puffer und Grössengrenze

- [x] Abgeschlossen und geprüft

Prüfstand 01.10.2026: PendingMessage und MessageBatch implementiert. Der zuerst
geschriebene Test scheiterte an den fehlenden Klassen; danach acht BatchRuleTest-
Prüfungen erfolgreich. Bei 500 Einträgen wird eine unveränderliche Kopie übergeben;
verschiedene Empfangskanäle werden innerhalb eines Stapels abgelehnt. Duplikat-
Lieferungen bleiben erhalten. Kein Insert, ACK oder Timeout. Der vollständige
Writer-Lauf hat 46 erfolgreiche Prüfungen und unverändert fünf Docker-Setup-Fehler,
keine übersprungenen Tests. Die offenen Infrastrukturprüfungen bleiben unter N1–N9
verzeichnet; für Aufgabe 07 ist kein zusätzlicher Nachholtest blockiert.

1. **Ziel:** Gültige Nachrichten mit ihren Lieferungsinformationen bis maximal 500 sammeln.
2. **Warum kommt dieser Schritt jetzt?** Die Sammellogik lässt sich ohne Datenbank und laufenden Consumer eindeutig prüfen.
3. **Welche Dateien werden verändert?** Neu `main/messaging/PendingMessage.java`, `main/service/MessageBatch.java`, `test/service/BatchRuleTest.java`.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=BatchRuleTest`: 499 Einträge noch nicht voll; der 500. schliesst den Stapel; kein leerer Schreibauftrag; keine Vermischung verschiedener Kanalzuordnungen.
5. **Was erwarten wir vor der Implementierung?** Puffer und Grössenauslöser fehlen.
6. **Was implementieren wir?** Einfache, begrenzte Stapelverwaltung mit eindeutiger Übergabe eines abgeschlossenen Stapels. Die Nachrichten-ID bleibt getrennt von der AMQP-Delivery-ID. Noch kein Insert und kein ACK.
7. **Was erwarten wir danach?** Grössentests grün, maximal 500 Einträge pro Stapel, keine gleichzeitige Weiterveränderung eines übergebenen Stapels.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S3, S4, S6 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: Nachrichten in begrenzten Stapeln sammeln`

## Aufgabe 08: Feste Sammelfrist von 200 ms

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Auch Teilstapel ohne weitere Lieferungen rechtzeitig freigeben.
2. **Warum kommt dieser Schritt jetzt?** Die Grössengrenze ist bereits unabhängig geprüft; jetzt kommt der zweite Auslöser dazu.
3. **Welche Dateien werden verändert?** `main/service/MessageBatch.java`, `test/service/BatchRuleTest.java`; nötige kleine Zeitprüflogik innerhalb der Batch-Verantwortlichkeit.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `BatchRuleTest` erweitern: vor 200 ms kein Teilstapel, bei 200 ms freigeben, neue Nachrichten verlängern die Frist nicht, leerer Puffer bleibt ohne Schreibauftrag. Zeit kontrolliert zuführen statt lange schlafen.
5. **Was erwarten wir vor der Implementierung?** Nur volle Stapel werden freigegeben; ein einzelner Eintrag bleibt liegen.
6. **Was implementieren wir?** Monotone Zeitmessung ab dem ersten gültigen Eintrag und eine unabhängig von neuen Nachrichten aufrufbare Fristprüfung. Gleichzeitiger Grössen-/Zeitauslöser darf keinen Stapel doppelt liefern.
7. **Was erwarten wir danach?** Beide Auslöser korrekt, gleiche Frist trotz weiterer Einträge. Der echte periodische Aufruf wird in Aufgabe 12 mit dem Consumer verbunden.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S3 und S4 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: Teilstapel nach 200 Millisekunden freigeben`

## Aufgabe 09: Atomarer JDBC-Batch mit Duplikatbehandlung

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Einen abgeschlossenen Stapel vollständig und idempotent speichern.
2. **Warum kommt dieser Schritt jetzt?** Datenmodell und Stapelstruktur sind bekannt. Die Datenbankgarantie muss vor dem ersten Consumer-ACK geprüft sein.
3. **Welche Dateien werden verändert?** Neu `main/repository/MessageRepository.java`, `main/service/BatchPersistenceService.java`, `test/service/MessagePersistenceIntegrationTest.java`.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=MessagePersistenceIntegrationTest`: alle sechs Felder, mehrere Zeilen in einer Transaktion, absichtlich erzwungener DB-Fehler mit vollständigem Rollback, gleiche ID innerhalb und nach einem Stapel.
5. **Was erwarten wir vor der Implementierung?** Kein Persistenzweg; keine gespeicherten Zeilen. Die neue Prüfung erfüllt noch keine Transaktions- oder Duplikatgarantie.
6. **Was implementieren wir?** Parametergebundenes `JdbcTemplate.batchUpdate`, eine explizite Transaktion pro Versuch und `ON CONFLICT (id) DO NOTHING`. Rückkehr erst nach abgeschlossenem Commit; kein Auto-Commit pro Zeile. Update-Zähler null ist bei Konflikt Erfolg. Zeitpunkte entsprechend spezifizierter Präzision prüfen.
7. **Was erwarten wir danach?** Neue Daten vollständig gespeichert, Duplikate unverändert übersprungen, Fehler rollt den gesamten Versuch zurück. Noch keine Queue-Bestätigung.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S3–S7 vorbereitet; Datenbankanteil von S5 nachgewiesen.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: Nachrichtenstapel atomar und ohne Duplikate speichern`

## Aufgabe 10: Dauer eines Datenbankversuchs begrenzen

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Ein Schreibversuch blockiert insgesamt höchstens fünf Sekunden gemäss Spezifikation 10.
2. **Warum kommt dieser Schritt jetzt?** Ein Retry um unbegrenzt blockierende Datenbankarbeit wäre wirkungslos. Die tatsächliche Schreiboperation existiert jetzt für die Messung.
3. **Welche Dateien werden verändert?** `resources/application.yml`, `main/service/BatchPersistenceService.java`; falls für konkrete JDBC-Einstellungen nötig neu `main/config/DatabaseConfig.java`; neu `test/service/DatabaseAttemptTimeoutIntegrationTest.java`.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=DatabaseAttemptTimeoutIntegrationTest`: fehlende Verbindung und blockierte SQL-Ausführung mit echtem PostgreSQL prüfen; verstrichene Zeit vom Beginn der Verbindungsbeschaffung bis Ende/Abbruch messen.
5. **Was erwarten wir vor der Implementierung?** Die normale Transaktion funktioniert, ihre Gesamtdauer ist noch nicht abgesichert. Eine zu lange Wartezeit muss als Testfehler sichtbar werden.
6. **Was implementieren wir?** Aufeinander abgestimmte Verbindungs-, Socket-, SQL- und Abbruchgrenzen innerhalb des Gesamtbudgets. Keine Annahme, dass ein Transaktions-Timeout allein genügt. Defekte Verbindungen verwerfen; keine parallele Fortsetzung alter und neuer Versuche. Konkrete Werte und Abbruchwirkung im Test belegen.
7. **Was erwarten wir danach?** Die spezifizierte Versuchsdauer ist für die Fehlerpfade gemessen; Erfolgspfad und Rollback-Tests bleiben grün. Wenn das Budget nicht nachweisbar ist, gilt diese Aufgabe als offen; keine stillschweigende Verlängerung der Spezifikation.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S7 vorbereitet, wesentliches Zeitrisiko früh geprüft.
9. **Vorgeschlagene deutsche Commit-Message:** `fix: Datenbankschreibversuche zeitlich begrenzen`

## Aufgabe 11: Drei Schreibversuche mit begrenzter Pause

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Schreibfehler höchstens zweimal wiederholen und ein eindeutiges Ergebnis an den späteren Consumer liefern.
2. **Warum kommt dieser Schritt jetzt?** Ein Versuch besitzt bereits die geprüfte Transaktions- und Zeitgrenze.
3. **Welche Dateien werden verändert?** Neu `main/service/BatchWriteService.java`, `test/service/BatchWriteServiceTest.java`; vorhandene Persistenztests für neue Versuche mit neuer Transaktion ergänzen.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=BatchWriteServiceTest`: Erfolg beim ersten/zweiten/dritten Versuch, drei Fehler, fünf Sekunden Pause vor jeder Wiederholung, unveränderter Stapel, kein vierter Versuch. Für die Ablaufprüfung kontrollierte Wartefunktion nutzen; echte Zeiten später in S7 messen.
5. **Was erwarten wir vor der Implementierung?** Ein Fehler beendet bisher einen einzelnen Versuch; Wiederholungsablauf fehlt.
6. **Was implementieren wir?** Drei lokale Versuche, je neue Transaktion, höchstens ein Versuch gleichzeitig, Fehlerlogs mit Versuch und Grund. Ergebnis unterscheidet Commit-Erfolg von erschöpften Schreibversuchen. Noch keine ACK-/NACK-Ausführung in dieser Klasse.
7. **Was erwarten wir danach?** Begrenzter, nachvollziehbarer Ablauf. Eine spätere Broker-Ausnahme kann nicht versehentlich als Datenbankschreibfehler gezählt werden.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S7 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: Fehlgeschlagene Stapel begrenzt wiederholen`

## Aufgabe 12: Consumer sicher mit Puffer und Schreibweg verbinden

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Erster vollständiger Weg von `chat.persist` bis Commit und ACK.
2. **Warum kommt dieser Schritt jetzt?** Decoder, Batch-Regeln, Transaktion und Wiederholung sind einzeln geprüft. Der Consumer kann sofort sichere Bestätigungsregeln verwenden.
3. **Welche Dateien werden verändert?** Neu `main/messaging/MessageConsumer.java`, `test/messaging/MessageConsumerIntegrationTest.java`; `main/config/RabbitConfig.java`, `resources/application.yml`; vorhandene Puffer-/Schreibklassen nur für die Verbindung ihrer Verantwortlichkeiten.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=MessageConsumerIntegrationTest`: echte Queue und DB; eine Nachricht ohne Folgelieferung sowie ein voller Stapel; vor Commit noch unbestätigt, nach Commit gespeichert und Queue leer.
5. **Was erwarten wir vor der Implementierung?** Nachrichten bleiben im Broker liegen; bisher gibt es keinen produktiven Consumer.
6. **Was implementieren wir?** Genau ein Consumer mit Prefetch 500 und manuellen Bestätigungen. Empfang blockiert nicht das Sammeln weiterer Lieferungen; Zeitprüfung läuft auch ohne neue Lieferung. Eine serialisierte Zustandsverwaltung verbindet Decoder, Batch und Writer. Nach Commit Einzel-ACK mit `multiple=false`; bei ungültiger Eingabe oder drei Schreibfehlern Einzel-NACK mit `requeue=false`. Kanal und Delivery-ID bleiben zugeordnet. Logs und Kommentare gleichzeitig ergänzen.
7. **Was erwarten wir danach?** Normaler End-to-End-Weg und grundlegende Fehlerzuordnung funktionieren, keine frühzeitigen ACKs. Weder ein ungeprüfter Auto-ACK-Zwischenstand noch ein temporärer Einzel-Insert-Consumer wird eingebaut.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** Technische Grundlage für S3–S7; volle Mengennachweise folgen.
9. **Vorgeschlagene deutsche Commit-Message:** `feat: Queue-Nachrichten nach Commit bestätigen`

## Aufgabe 13: Ungültige Nachrichten und DLQ isoliert nachweisen

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Fehlerhafte Nachrichten können gültige Stapel nicht beschädigen oder blockieren.
2. **Warum kommt dieser Schritt jetzt?** Der komplette Fehlerpfad ist angeschlossen und kann am echten Broker geprüft werden.
3. **Welche Dateien werden verändert?** Neu `test/messaging/InvalidMessageIntegrationTest.java`; bei gefundenen Fehlern `main/messaging/MessageDecoder.java` und `main/messaging/MessageConsumer.java`.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=InvalidMessageIntegrationTest`: ungültiges JSON, falscher Typ, fehlendes Feld und falsche Metadaten zwischen gültigen Lieferungen; anschliessend alle gültigen IDs in DB und ausschliesslich ungültige Bodies in DLQ prüfen.
5. **Was erwarten wir vor der Implementierung?** Grundpfad kann schon grün sein; seine Isolation und Body-Erhaltung sind noch nicht vollständig nachgewiesen.
6. **Was implementieren wir?** Den Integrationstest und nur notwendige Korrekturen am vorhandenen Fehlerpfad. Kein selbst programmiertes Publish-plus-ACK zur DLQ, keine neue Retry-Queue. Ungültiger Eintrag verändert nicht die Sammelfrist gültiger Einträge.
7. **Was erwarten wir danach?** Fehlerhafte Bodies unverändert in DLQ, gültige Nachrichten gespeichert, keine offenen Lieferungen oder Endlosschleifen.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** Fehlerfallspezifikation; schützt den gültigen S5-Pfad, ergänzt S1/S8.
9. **Vorgeschlagene deutsche Commit-Message:** `test: Ungültige Nachrichten und Dead-Letter-Verhalten prüfen`

## Aufgabe 14: Kanalverlust und geordnetes Beenden

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Kanalfehler nach Commit verursachen weder falsche Retries noch Datenverlust.
2. **Warum kommt dieser Schritt jetzt?** Die ACK-/NACK-Grenze ist vorhanden; ihre Unterbrechung lässt sich jetzt gezielt testen.
3. **Welche Dateien werden verändert?** `main/messaging/MessageConsumer.java`; neu `test/messaging/ConsumerLifecycleIntegrationTest.java`; Konfiguration nur für konkret benötigte Stop-/Recovery-Einstellungen.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=ConsumerLifecycleIntegrationTest`: Kanal nach Commit vor vollständigem ACK schliessen, erneut zustellen lassen; zusätzlich Stop mit ungespeichertem Teilstapel.
5. **Was erwarten wir vor der Implementierung?** Normaler Weg ist grün, sichere Behandlung alter Kanalreferenzen und Stop-Verhalten noch nicht belegt.
6. **Was implementieren wir?** Alte Lieferungsreferenzen nach Kanalverlust verwerfen; keine ACKs auf neuem Kanal mit alten IDs. ACK-Fehler erhöhen keinen DB-Versuchszähler und führen nicht zur DLQ. Bei Stop keine neuen Stapel beginnen; nur committed Daten bestätigen, Rest durch Kanalschluss erneut verfügbar lassen.
7. **Was erwarten wir danach?** Wiederzugestellte Daten bleiben eindeutig; Eingangsqueue wird nach Recovery leer, keine DLQ-Nachricht nur aufgrund eines ACK-Fehlers. Unbestätigte Stop-Reste bleiben wieder zustellbar.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S5–S7 abgesichert, zusätzlich spezifiziertes Stop-Verhalten.
9. **Vorgeschlagene deutsche Commit-Message:** `fix: Kanalverlust nach Datenbank-Commit sicher behandeln`

## Aufgabe 15: S5 als vollständigen Duplikattest nachweisen

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Genau das headerarme Duplikatszenario des Auftrags bestehen.
2. **Warum kommt dieser Schritt jetzt?** Nach Kanal-Recovery muss auch der gesamte Weg von JSON bis ACK bei erneuter Lieferung stimmen.
3. **Welche Dateien werden verändert?** Neu `test/service/DuplicateMessageIntegrationTest.java`; Fehlerkorrekturen nur an den unmittelbar betroffenen Decoder-/Persistenz-/Consumer-Dateien.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=DuplicateMessageIntegrationTest`: denselben rohen UTF-8-Body zweimal direkt per AMQP, nur `content_type=application/json`; kein Producer-Konverter, kein HTTP für diese Duplikate.
5. **Was erwarten wir vor der Implementierung?** Einzelkomponenten sind geprüft; der exakte S5-Nachweis fehlt und kann bereits beim ersten Lauf grün sein.
6. **Was implementieren wir?** Testfälle innerhalb eines Stapels, nach vorherigem Commit und gemischt mit einer neuen ID. Inhalt und ID unverändert vergleichen, null Insert-Zähler als Erfolg behandeln, Queue-Zustände prüfen.
7. **Was erwarten wir danach?** Pro Test-ID genau eine DB-Zeile, keine wartenden oder unbestätigten Lieferungen, DLQ leer. Keine unbemerkte Java-Typheader-Abhängigkeit.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S5 im Integrationstest nachgewiesen; Compose-Abnahme bleibt Aufgabe 21.
9. **Vorgeschlagene deutsche Commit-Message:** `test: Duplikate ohne Java-Typheader nachweisen`

## Aufgabe 16: Zwei Writer und konkurrierende Inserts

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Zwei unabhängige Writer konsumieren gemeinsam ohne doppelte Zeilen.
2. **Warum kommt dieser Schritt jetzt?** Der einzelne Writer samt Duplikatbehandlung und Kanal-Lebenszyklus ist geprüft. Parallelität kommt als nächste Fehlerquelle hinzu und wird vor S7 getestet.
3. **Welche Dateien werden verändert?** Neu `test/service/MultipleWritersIntegrationTest.java`; nur bei Testbefunden betroffene Consumer-/Batch-/Persistenz-Dateien korrigieren.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=MultipleWritersIntegrationTest`: zwei getrennte Writer-Anwendungskontexte/Verbindungen, gemeinsamer Broker und DB, zwei Consumer und 1'000 eindeutige Nachrichten. Separater synchronisierter Fall für überlappende Transaktionen mit derselben ID.
5. **Was erwarten wir vor der Implementierung?** Ein-Instanz-Verhalten ist belegt; gemeinsame statische Zustände oder konkurrierende Inserts sind noch nicht ausgeschlossen.
6. **Was implementieren wir?** Den Paralleltest; bei Bedarf Zustand tatsächlich instanzlokal machen. Kein globales Schloss, keine Raumverteilung, keine exklusive Queue. Datenbankkonflikt gezielt provozieren statt auf zufällige zeitliche Überlappung zu hoffen.
7. **Was erwarten wir danach?** Zwei Consumer, alle IDs genau einmal gespeichert; konkurrierendes Duplikat ebenfalls nur eine Zeile, beide Lieferungen abgeschlossen, DLQ leer. Keine 50:50-Verteilung verlangen.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S6 im Integrationstest nachgewiesen; Voraussetzung für den S7-Aufbau.
9. **Vorgeschlagene deutsche Commit-Message:** `test: Zwei parallele batch-writer prüfen`

## Aufgabe 17: Datenbankausfall mit beiden Writern

- [ ] Abgeschlossen und geprüft

1. **Ziel:** S7 mit 15 Sekunden Ausfall, spezifiziertem Fehlerziel und Erholung beider Instanzen nachweisen.
2. **Warum kommt dieser Schritt jetzt?** Retry, Zeitgrenzen und Mehrinstanzbetrieb sind einzeln abgesichert; nun wird ihre Kombination geprüft.
3. **Welche Dateien werden verändert?** Neu `test/service/DatabaseOutageIntegrationTest.java`; nur bei Befunden betroffene Konfigurations-, Schreib- oder Consumer-Dateien korrigieren.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn -pl batch-writer test -Dtest=DatabaseOutageIntegrationTest`: echte DB mit bereits benutzten Verbindungen stoppen, 300 Nachrichten einspeisen, DB unabhängig vom Senden nach 15 Sekunden wieder starten; zwei Writer bleiben aktiv.
5. **Was erwarten wir vor der Implementierung?** Der kombinierte Fehlerfall ist noch nicht gemessen. Ein reiner Mock-Test der drei Versuche genügt nicht.
6. **Was implementieren wir?** Zeitgesteuerten Integrationstest mit unverändertem DB-Datenvolume, Aufzeichnung der IDs, nicht entfernender DLQ-Prüfung und Kontrollnachrichten nach abgeschlossener Behandlung. Bei Fehlern Ursache beheben; weder 90 Sekunden verlängern noch Nachrichten still verwerfen oder automatisch aus DLQ zurückführen.
7. **Was erwarten wir danach?** Binnen 90 Sekunden alle 300 IDs in der Vereinigung DB/DLQ, keine davon offen in `chat.persist`; danach beide Writer mit neuem Commit ohne Neustart. Früh erschöpfte Versuche dürfen zur DLQ führen. Kontrollprüfung gemäss Spezifikation 19.8 zusätzlich durchführen.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S7 im Integrationstest nachgewiesen; Pflicht-Ausfalltest für die Abgabe.
9. **Vorgeschlagene deutsche Commit-Message:** `test: PostgreSQL-Ausfall und automatische Erholung nachweisen`

## Aufgabe 18: Writer-Image und vollständige Compose-Integration

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Den geprüften Writer als skalierbaren Container im Gesamtstack starten.
2. **Warum kommt dieser Schritt jetzt?** Fehler im Java-Kern sind weitgehend isoliert geprüft; jetzt werden Verpackung und Containerkonfiguration geprüft.
3. **Welche Dateien werden verändert?** Neu `batch-writer/Dockerfile`; `docker-compose.yml`; `chat-service/Dockerfile` nur falls der gemeinsame Buildkontext noch angepasst werden muss. `.env.example` bleibt bei den bereits eingeführten Variablen.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `docker compose config --format json`, danach `docker compose build batch-writer`. Vorher fehlen Writer-Dienst und Dockerfile. Nach Umsetzung Prüfungen aus Spezifikation 19.3 und 19.7 ausführen.
5. **Was erwarten wir vor der Implementierung?** Java-Tests grün, aber das Containerziel `batch-writer` existiert noch nicht.
6. **Was implementieren wir?** Mehrstufiges Dockerfile, Writer-Service mit internen Hosts und Zugangsdaten, Abhängigkeit von gesunden Diensten, kein Host-Port und kein fester Containername. RabbitMQ erhält `rabbitmq-data`. Build-Tests nicht im Docker-Build ausführen, sondern vorher mit Maven; keine `.env` ins Image kopieren.
7. **Was erwarten wir danach?** Frischer Stack mit vier Diensten, Schema vorhanden, alle ohne Port-Mappings; `--scale batch-writer=2` ergibt zwei Consumer. Volumes bleiben bei normalem Neustart erhalten. Start auf bestehenden Testvolumes ersetzt nicht den Frischstartnachweis.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S2 sowie Containeranteil von S6 lokal geprüft; kompletter Ablauf noch offen.
9. **Vorgeschlagene deutsche Commit-Message:** `chore: batch-writer als skalierbaren Container integrieren`

## Aufgabe 19: HTTP-Mengen und Transaktionsgrenze messen

- [ ] Abgeschlossen und geprüft

1. **Ziel:** S3 und besonders S4 am echten Compose-Stack reproduzierbar prüfen.
2. **Warum kommt dieser Schritt jetzt?** Erst mit dem Containerpfad werden tatsächlicher Empfang, Prefetch, Batch-Zeit und Datenbank-Nebenverkehr gemeinsam sichtbar.
3. **Welche Dateien werden verändert?** Neu `scripts/test-batch-writer.ps1` als temporäre Testclients steuerndes Prüfskript und `docs/test-batch-writer.md` als Messprotokoll; bei Fehlern eng begrenzte Korrekturen an den betroffenen Writer-Dateien. Kein load-generator-Dienst.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** Zuerst die S3-/S4-Befehle aus Spezifikation 19.4–19.5 manuell ausführen und Werte festhalten. Danach denselben Ablauf über `powershell -NoProfile -File scripts/test-batch-writer.ps1` reproduzierbar machen; aktuelle Skriptstufe deckt zunächst S3/S4 ab.
5. **Was erwarten wir vor der Implementierung?** Funktionale Tests sind grün; die reale Obergrenze von 100 Transaktionen ist noch nicht belegt. Zwei 500er-Stapel werden nicht vorausgesetzt.
6. **Was implementieren wir?** Nachvollziehbare Sendeschleifen, neue Raum-ID pro Szenario, Vergleich der angenommenen IDs, Prüfung von ready und unacknowledged, konservative DB-Statistikmessung ohne SQL-Polling im S4-Messfenster. Skript bricht bei verfehlten Kriterien mit Fehler ab und löscht keine Nutzdaten. Nur bei Bedarf Batch-Fortschritt korrigieren; 200-ms-Regel nicht umgehen.
7. **Was erwarten wir danach?** S3: 1'000 IDs binnen 60 Sekunden gespeichert. S4: vor Writer-Start exakt 1'000 wartend, danach alle gespeichert, Queue leer und gemessene Transaktionsdifferenz höchstens 100. Tatsächliche Werte, Umgebung und Messgrenzen im Protokoll, keine erfundenen Ergebnisse.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S3 und S4 lokal nachgewiesen; Messwerkzeug für Aufgabe 21 vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `test: Nachrichtenmengen und Transaktionsgrenze messen`

## Aufgabe 20: README und nachvollziehbarer Betrieb

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Den tatsächlich erreichten Stand und die Prüfung aus einem Klon verständlich dokumentieren.
2. **Warum kommt dieser Schritt jetzt?** Start- und Prüfabläufe wurden ausgeführt; die README beschreibt überprüftes Verhalten statt einen vorweggenommenen Erfolg.
3. **Welche Dateien werden verändert?** `README.md`; `docs/test-batch-writer.md` nur zur Ergänzung der reproduzierbaren Prüfvoraussetzungen.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** Aktuelle README gegen den laufenden Stack prüfen; danach Startanleitung mit `.env.example` in einem frischen Testklon nachvollziehen und sämtliche angegebenen Pfade/Befehle kontrollieren.
5. **Was erwarten wir vor der Implementierung?** README bezeichnet Writer/Postgres noch als ausstehend und erklärt deren Konfiguration/Fehlerziel nicht.
6. **Was implementieren wir?** Standtabelle aktualisieren, Variablen erklären, internes Testen ohne Host-Ports, Skalierung und Links auf Spezifikation/Plan/Testprotokoll dokumentieren. DLQ nach drei Schreibfehlern und fehlende automatische Rückführung ausdrücklich nennen.
7. **Was erwarten wir danach?** Eine andere Schülerin kann Start und Prüfung nachvollziehen. Keine Aussage über ein bestandenes vollständiges S1–S8-Ergebnis vor Aufgabe 21.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** Reproduzierbarkeit von S2–S7; geforderte README-Abgabe vorbereitet.
9. **Vorgeschlagene deutsche Commit-Message:** `docs: Start und Prüfung des batch-writer beschreiben`

## Aufgabe 21: Vollständiger S1–S8-Durchlauf und Abschlussprüfung

- [ ] Abgeschlossen und geprüft

1. **Ziel:** Alle Szenarien in der offiziellen Reihenfolge und auf demselben Stack nachvollziehbar abnehmen.
2. **Warum kommt dieser Schritt jetzt?** Erst jetzt sind Modul, Tests, Image, Stack, Messungen und Dokumentation vollständig vorhanden.
3. **Welche Dateien werden verändert?** `scripts/test-batch-writer.ps1` um vollständigen Ablauf ergänzen, `docs/test-batch-writer.md` mit realen Messwerten vervollständigen; bei konkreten Befunden ausschliesslich betroffene Dateien korrigieren und relevante Prüfungen wiederholen.
4. **Welcher Test wird zuerst geschrieben oder ausgeführt?** `mvn clean test` in der Wurzel, Testberichte kontrollieren. Anschliessend frischen Testklon mit `.env.example` verwenden und S2–S8 anhand Spezifikation 19 durchführen. Das Prüfskript erhält die noch fehlenden überprüfbaren S2-/S5-/S6-/S7-Kontrollen, ohne die laufenden Daten zu löschen. S8 zusätzlich manuell prüfen.
5. **Was erwarten wir vor der Implementierung?** Einzelne Szenarien sind belegt, ihre aufeinanderfolgende Ausführung mit bestehenbleibenden Daten und zwei Writern bei S7 noch nicht.
6. **Was implementieren wir?** Prüfablauf und ehrliches Protokoll: erwarteter/gemessener Wert, Dauer, Ergebnis und Nachweis pro Szenario. S5 rohe JSON-Duplikate nur mit `content_type`; S6 zwei Consumer; S7 beide weiterbetreiben, DB nach 15 Sekunden starten, 300 IDs binnen 90 Sekunden DB/DLQ zuordnen und beide Writer danach prüfen. S8 Streams, Kommentare und `.env` prüfen. Keine Tag-/Push-Automatik im Testskript.
7. **Was erwarten wir danach?** S1–S8 nachweislich bestanden oder konkrete offene Fehler ausdrücklich benannt. Für den erfolgreichen Abschluss: alle acht bestanden, Spec/Plan/Code/Tests vorhanden, Kommentare vollständig, `git diff --check` sauber, `git ls-files -- .env` leer, privater Erklärungsordner weiterhin ignoriert. Die finale geprüfte Codeversion wird im Protokoll eindeutig angegeben; der Protokoll-Commit darf anschliessend nur Prüfdokumentation bzw. Planstatus ergänzen. Eine nachträgliche Codekorrektur erfordert neue passende Nachweise.
8. **Welches Bewertungsszenario wird vorbereitet oder erfüllt?** S1–S8 vollständig nachzuweisen; zusätzliche Review-Vorbereitung anhand eigener Codezeilen.
9. **Vorgeschlagene deutsche Commit-Message:** `test: Abnahme des batch-writer nach S1 bis S8 dokumentieren`

## Zuordnung und Reihenfolge der Nachweise

| Thema / Szenario | Vorbereitende Aufgaben | Entscheidender Nachweis |
|---|---|---|
| S1: Root-Build und Tests | 01, 03, 05; danach fortlaufend | 21: ein vollständiger Lauf, alle Tests tatsächlich ausgeführt |
| S2: Frischer Compose-Stack | 01, 03–06, 18, 20 | 21: frischer Klon mit Beispielkonfiguration |
| S3: 1'000 HTTP-Nachrichten | 02, 07–12 | 19 und 21: IDs, 60 Sekunden, Queue vollständig leer |
| S4: höchstens 100 Transaktionen | 07–10, 12 | 19 und 21: Rückstau und reale Datenbankmessung |
| S5: Duplikat ohne Typheader | 02, 09, 12, 14 | 15 und 21: genau eine Zeile, DLQ leer |
| S6: zwei Writer | 06–09, 12, 14 | 16, 18 und 21: zwei Consumer und Konkurrenzprüfung |
| S7: Datenbankausfall | 10–12, 14, 16 | 17 und 21: echte Unterbrechung, Fehlerziel und Recovery beider Writer |
| S8: Stil und Geheimnisse | Jede Aufgabe, insbesondere 01, 04, 20 | Laufende Sichtprüfung und Abschlusskontrolle in 21 |

Die Schritte 09–12 dürfen nicht zu einem frühen Consumer mit vorläufigem Auto-ACK umsortiert werden. Duplikatbehandlung ist von der ersten Datenbankintegration an vorhanden. Die späteren Aufgaben 15–17 ergänzen gezielte Nachweise, sie führen diese Grundgarantien nicht erst nachträglich ein.

## Warum diese Reihenfolge das Risiko begrenzt

Das Modul kommt zuerst, damit alle folgenden Schritte einen bekannten Build- und Testweg besitzen. JSON-Vertrag und Schema werden vor der Infrastrukturverknüpfung geprüft; falsche Feldtypen lassen sich dann ohne Timer- oder Brokerfehler erkennen. Das Schema liegt vor dem ersten Postgres-Compose-Start vor, damit die einmalige Initialisierung nicht versehentlich ohne Tabelle erfolgt.

Grössengrenze und Zeitgrenze werden getrennt geprüft. Die Persistenz erhält Transaktion und Konfliktbehandlung gemeinsam, weil ein vorläufiger nicht idempotenter Schreibweg spätere Wiederholungen unsicher machen würde. Zuerst wird ein einzelner Datenbankversuch begrenzt, danach der Retry-Ablauf darum gebaut. Erst dann wird der Consumer angebunden. So kann er ACK nach Commit und endgültiges NACK von Anfang an korrekt unterscheiden.

Ungültige Nachrichten, Kanalverlust und rohe Duplikate werden vor mehreren Instanzen untersucht. Mehrinstanzbetrieb wird vor dem DB-Ausfall getestet, weil S7 die zwei Writer aus S6 übernimmt. Sonst könnte ein Ausfalltest mit nur einem frischen Writer Probleme verschweigen.

Die Containerintegration folgt auf den geprüften Kern. Danach werden reale Mengen und Transaktionen gemessen; Unit-Tests und Prefetch-Werte allein können S4 nicht beweisen. Die README folgt auf die tatsächlich ausgeführten Abläufe. Zum Schluss prüft ein zusammenhängender Durchlauf, ob getrennt grüne Tests auch im geforderten Gesamtzustand bestehen.

Dieser Plan ändert die Spezifikation nicht. Falls während der Umsetzung eine Vorgabe technisch nicht erfüllt werden kann, wird der Konflikt vor einer Verhaltensänderung offengelegt. Der spätere Abgabe-Tag `bewertung-1` und ein Push sind eigene Aktionen nach erfolgreicher Prüfung, keine automatische Folge dieses Plans.
