# Prüfprotokoll batch-writer – Aufgabe 21

## Docker-Nachholung vom 02.10.2026 – aktueller Stand

**S1–S7 in vollständigen Maven- und anschliessenden Compose-/Fortsetzungsläufen
praktisch nachgewiesen. S8 bleibt wegen des manuellen Stilreviews offen.**
Die historischen Aussagen unterhalb dieses Abschnitts beschreiben den früheren
Stand ohne Docker und werden durch diese Ergebnisse ersetzt.

Umgebung: Docker Desktop mit Engine 29.8.1 (Linux), Compose v5.5.1,
Windows PowerShell 5.1, Java 21.0.10 und Maven 3.9.11. Docker wurde über seinen
Installationspfad in den Prozess-PATH aufgenommen. Keine globale Git- oder
PowerShell-Richtlinie verändert. Testklon ohne persönliche `.env`; neue Volumes.

| Prüfung | Tatsächliches Ergebnis |
|---|---|
| S1: Root `mvn clean test` auf `f6644fe` | **141 Tests erfolgreich**, davon 14 chat-service und 127 batch-writer; keine Fehler/Fehlschläge/Skips |
| S2: Image-Build und frischer Compose-Stack | Vier Dienste, korrektes Schema/PK/Index, gemeinsames `chat-net`, keine Host-Port-Mappings, Consumer verbunden |
| S3: 1.000 HTTP-Nachrichten | Alle Antwort-IDs und Inhalte innerhalb der 60-Sekunden-Prüfgrenze gespeichert; Eingangsqueue leer |
| S4: 1.000 wartende Nachrichten | Rohe Transaktionsdifferenz **6**, Ausgangswert 54, Endwert 60; alle IDs gespeichert; Grenze 100 eingehalten |
| S5: identischer roher Body zweimal | Genau eine unveränderte Zeile, beide Lieferungen bestätigt, DLQ leer |
| S6: zwei Writer | Zwei Container/Consumer, alle 1.000 IDs gespeichert, keine neuen DLQ-Einträge |
| S7: PostgreSQL-Ausfall | Neustart nach **15,1082155 Sekunden** angestossen; alle 300 IDs binnen 90 Sekunden DB/DLQ zugeordnet; beide ursprünglichen Writer danach erfolgreich, ohne Neustart |
| Echte Integrationstests | Duplikate, ungültige Bodies/DLQ, Kanalverlust/Stop, Batch-Commit/Rollback, Fünf-Sekunden-Grenzen, parallele Writer und DB-Ausfall bestanden |
| Datenerhalt nach Container-Ersatz | PostgreSQL und RabbitMQ nach S7 mit denselben Volumes ersetzt: **4.381 Zeilen** und vollständige Inhaltsprüfsumme unverändert; **220 DLQ-Nachrichten** erhalten; Eingangsqueue leer |
| Stop der zwei untätigen Writer | Zusammen **0,778 Sekunden**; kein Nachweis einer maximalen Stop-Dauer unter beliebiger Last |
| S8 | Keine getrackte `.env`, Ignore-Regel wirksam, keine Streams; vollständige manuelle Stilfreigabe weiterhin offen |

### Gefundene Ursachen und gezielte Korrekturen

- `ed7d629`: Asynchrone DB-Prüfung wartet auf eine noch fehlende Zeile; mehrere
  Kontrollnachrichten werden mit einer Sammelabfrage vollständig verglichen.
  Aktuelle Queue-Tiefe wird passiv beim Broker statt aus verzögerten Statistiken
  gelesen. ACK- und Unacked-Prüfungen bleiben erhalten.
- Derselbe Testcommit beseitigt den Mockito-Spy auf einem Spring-Proxy und gibt
  dem Ausfalltest eine stabile Host-Port-Bindung. Die produktive Compose-Datei
  veröffentlicht weiterhin keine Ports.
- Der Commit-Timeout-Test berücksichtigt ein ungewisses Commit-Ergebnis:
  Client-Timeout bedeutet nicht zwingend serverseitiger Rollback. Nach begrenztem
  Warten auf serverseitige Bereinigung muss die Wiederholung genau eine Zeile
  hinterlassen. Die Fünf-Sekunden-Grenze des Schreibaufrufs bleibt unverändert.
- `f6644fe`: RabbitMQ-Healthcheck prüft laufende Anwendung und Listener.
  `ping` allein meldete zu früh Bereitschaft; der Writer konnte dadurch beim
  Queue-Initialisieren abbrechen.
- Das Abnahmeskript wartet innerhalb der bestehenden Frist auch bei noch
  fehlender Queue/HTTP 404. Der PostgreSQL-Neustartjob wertet den Docker-Exitcode
  aus; normale Fortschrittsausgabe auf stderr ist kein Neustartfehler.

### Herkunft und Grenzen der Nachweise

Der erste Root-Lauf fand fünf Writer-Testfehler; danach wurden die Ursachen
gezielt korrigiert und erneut geprüft. Zwei anschliessende vollständige Root-Läufe
auf `ed7d629` und `f6644fe` bestanden mit jeweils 141 Tests.

Die Compose-Abnahme ist **kein einziger unterbrechungsfreier Lauf des endgültigen
Skripts**. Nach Korrektur der Queue-Bereitschaftsprüfung wurden S2–S6 auf demselben
frisch gestarteten Stack fortgesetzt. S7 wurde nach Korrektur der PowerShell-
Jobauswertung mit neuen Nachrichten und denselben beiden Writern wiederholt.
Zwischen S2 und S7 wurden keine Tabellen, Queues oder Volumes geleert. Der
zusätzliche Container-Ersatz erfolgte erst nach erfolgreichem S7. Die vorhandenen
220 DLQ-Nachrichten stammen aus den Ausfallprüfungen, nicht aus S5.

Private Belege im Workspace unter `Erklärungen/`:

- `Docker-Nachholung-vollstaendig.log` und `Docker-Nachholung-vollstaendig-2.log`:
  vollständige Root-Läufe und erste Compose-Startversuche.
- `Docker-Compose-Fortsetzung.log`: S2–S6 und gemessene S4-Differenz.
- `Docker-S7-Fortsetzung.log`: erfolgreicher S7-Wiederholungslauf.
- `Docker-Datenerhalt.txt`: Datenprüfsummen, Queue-Zähler und Stop-Dauer.
- Testklon `docker-abnahme-20261002/Erklärungen/Schritt 6 Code Beginn/`:
  `Abnahme-20261002-201456` enthält S2–S6-Resultate und HTTP-Belege;
  `Abnahme-20261002-201734` enthält S7-Resultate und HTTP-Belege.

Diese privaten Dateien werden nicht committed. Für einen weiteren einzelnen
Durchlauf des endgültigen Skripts einen neuen Testklon und freie `chat-net`-
Umgebung verwenden; bestehende Testvolumes nicht ungefragt löschen.

## Historisches Protokoll vor der Docker-Nachholung

Stand: 02.10.2026. **Teilabnahme; S1–S8 nicht vollständig bestanden.**
Docker/Compose fehlen in der aktuellen Arbeitsumgebung. Bekanntermassen blockierte
Containerprüfungen wurden gemäss Benutzeranweisung nicht erneut gestartet.
Die frühere Zurückstellung der Tests endet mit dem ausdrücklich beauftragten
Abnahmeabschnitt 21. Es werden keine Tests deaktiviert oder bei fehlendem Docker
als erfolgreich übersprungen.

## Geprüfter Stand und Umgebung

Produktionscode unverändert gegenüber `8805bef`; dieser Aufgaben-21-Commit ergänzt
Testcode, Prüfskripte und Dokumentation. Erkennbar über
`git log -1 --format=%H -- scripts/test-batch-writer.ps1`.
Ein späterer vollständiger Abnahmelauf muss seinen tatsächlichen Commit und seine
Messwerte ergänzen; dieses Dokument enthält keine vorweggenommenen Ergebnisse.

- Windows PowerShell 5.1, Java 21.0.10, Maven 3.9.11.
- Maven war nicht im PATH, aber im lokalen Maven-Wrapper-Cache vorhanden; für die
  unten genannten Befehle wurde dessen `bin/mvn.cmd` aufgerufen.
- Ein erster Offline-Aufruf erreichte wegen der Parent-POM-Auflösung keine Tests.
  Der anschliessende reguläre Maven-Aufruf war erfolgreich. Keine POM-Version geändert.
- Python 3.14 lokal für Syntax- und isolierte Clientprüfungen; der spätere interne
  Testclient verwendet `python:3.12-alpine`. Kein lokales Python für den Compose-Test nötig.
- Keine Docker-Images gebaut, keine Produktcontainer gestartet, keine reale
  Datenbank-Transaktionszahl, Ausfallzeit oder Queue-Laufzeit gemessen.

## Tatsächlich ausgeführte Prüfungen

```powershell
mvn -pl batch-writer test '-Dtest=ApplicationConfigurationTest,MessageDecoderTest,BatchRuleTest,DatabaseAttemptTimeoutTest,BatchWriteServiceTest,MessageConsumerTest'
mvn -pl chat-service test '-Dtest=MessageServiceTest,SendMessageRequestTest,MessageExceptionHandlerTest,ChatServiceApplicationTest'
mvn -pl batch-writer test-compile
```

| Prüfung | Ergebnis | Grenze des Nachweises |
|---|---|---|
| Writer: sechs ausgewählte Testklassen | 91 Tests, 0 Fehler, 0 Fehlschläge, 0 übersprungen | Keine echten Broker-/DB-Verbindungen |
| chat-service: vier ausgewählte Testklassen | 8 Tests, 0 Fehler, 0 Fehlschläge, 0 übersprungen | Kein vollständiger Root-Lauf |
| Alle Writer-Testquellen übersetzen | Erfolgreich, einschliesslich neuer Integrationstests | Übersetzen ist kein ausgeführter Integrationstest |
| PowerShell-Syntax und UTF-8-Pfad unter 5.1 | Erfolgreich | Docker-Ablauf nicht ausgeführt |
| Isolierter PowerShell-ID-/Inhaltsvergleich | Passende IDs akzeptiert; fremde IDs, leere Ergebnisse, geänderte Inhalte und doppelte Antwort-IDs abgelehnt | Datenbank bzw. HTTP-Antworten ersetzt |
| PowerShell-Prozessauswertung | Warnung auf stderr mit Exitcode 0 akzeptiert, Exitcode 7 abgelehnt | Kontrollierter Ersatzprozess statt Maven/Docker |
| Python-Client | Syntax gültig; 300 eindeutige Testantworten zugeordnet; HTTP-Status ungleich 202 abgelehnt | HTTP-Funktion ersetzt; keine Lastmessung |
| `.env`, privater Ordner, Stream-Suche | `.env` nicht getrackt; beide ignoriert; keine Java-Streams gefunden | Ersetzt nicht die vollständige manuelle S8-Prüfung |

Die 91 Writer-Prüfungen verteilen sich auf Konfiguration (6), Decoder (34),
Batch-Regel (14), DB-Versuchsbegrenzung (4), Retry (11) und Consumer (22).
Die neuen acht Consumer-Fälle behandeln Proxy-Kanalwechsel, verspätetes
Schliessereignis, NACK-Fehler, partielle ACKs, Stop mit Teilstapel, Stop während
erfolgreicher/fehlgeschlagener Arbeit sowie die abgelaufene Stop-Schonfrist.

Die gezielte Testauswahl ist ausdrücklich **kein** `mvn clean test` und erfüllt S1
noch nicht. Alte Integrationstest-Berichte dürfen nicht mit diesen aktuellen
Ergebnissen zusammengerechnet werden.

## Neu vorbereitete Integrationstests

| Klasse | Inhalt | Ausführung |
|---|---|---|
| `DuplicateMessageIntegrationTest` | Rohe AMQP-Nachrichten nur mit `content_type`, Duplikate in kontrolliert demselben Stapel und nach Commit, neue Nachbar-ID; zusätzlicher Producer-Typheader | Noch nicht ausgeführt |
| `ConsumerLifecycleIntegrationTest` | Echter Kanalverlust vor erstem/nach einzelnem ACK, Wiederzustellung mit eindeutigen Zeilen; echter Teilstapel-Stop ohne Insert | Noch nicht ausgeführt |
| `MultipleWritersIntegrationTest` | Zwei produktive Kontexte und 1'000 IDs; gezielt überlappende Inserts derselben ID mit beobachtetem PostgreSQL-Lock und echten ACKs | Noch nicht ausgeführt |
| `DatabaseOutageIntegrationTest` | Zwei Writer mit zuvor benutzten DB-Verbindungen, derselbe PostgreSQL-Container 15 Sekunden weg, 300 IDs in DB/DLQ binnen 90 Sekunden, Erholung beider ursprünglicher Instanzen | Noch nicht ausgeführt |

`WriterTestStack` teilt nur den Testaufbau: isolierte Container mit produktivem
Schema, rohe AMQP-Veröffentlichung, unabhängige DB-Abfragen und nicht entfernende
Brokerdiagnose. Die ACK-Statistik wird mitgeprüft, damit eine kurzzeitig veraltete
leere Queue bei Duplikaten nicht irrtümlich als Abschluss gilt. Die produktiven
DB-Zeitgrenzen bleiben auch beim synchronisierten Konkurrenztest unverändert.
Die Stabilität dieser neuen Integrationstests muss erstmals mit Docker geprüft werden.

## Vollständigen Durchlauf nachholen

Voraussetzungen: Java 21, Maven, Docker mit erreichbarem Server, Compose mit
`up --wait`, Zugriff auf Maven-/Container-Registries. Einen **frischen Testklon**
des zu prüfenden Commits verwenden, ohne persönliche `.env`, ohne bestehendes
`chat-net` und ohne bestehende Volumes dieses Compose-Projekts. Der aktuelle
Entwicklungsstack wird nicht gelöscht oder umkonfiguriert.

```powershell
docker version
docker compose version
powershell -NoProfile -File scripts/test-batch-writer.ps1
# Falls Maven nicht im PATH steht, stattdessen einen vorhandenen Pfad übergeben:
powershell -NoProfile -File scripts/test-batch-writer.ps1 -MavenCommand 'C:\Pfad\apache-maven\bin\mvn.cmd'
```

Die beiden Skriptaufrufe sind Alternativen, keine aufeinanderfolgenden Läufe.
Das Skript verwendet `.env.example` direkt. Es prüft den Root-Lauf und tatsächliche
Surefire-Ergebnisse ohne übersprungene Tests. Danach folgen S2–S7 auf demselben
Stack. S7 übernimmt die zwei Writer aus S6. Es gibt kein Queue-Purge, kein
TRUNCATE, keine Volume-Löschung, keinen automatischen Tag und keinen Push.

Der kurzlebige Python-Client läuft im `chat-net`, veröffentlicht keinen Host-Port
und nutzt nur die Standardbibliothek. Er sendet HTTP-Nachrichten, fragt den Broker
ab und veröffentlicht für S5 identische rohe JSON-Bodies über die Management-API
auf den Default-Exchange. Dabei wird als einzige AMQP-Eigenschaft `content_type`
gesetzt. Das ist kein neuer Compose-Dienst und kein load-generator.

Die privaten Ergebnisse entstehen in
`Erklärungen/Schritt 6 Code Beginn/Abnahme-<Zeitpunkt>/`: `results.json`,
`receipts.json`, `maven.log`, `writer.log`. Im Testklon wird der private Ordner bei
Bedarf ausschliesslich in `.git/info/exclude` aufgenommen. Die Anwendung und ihre
Daten bleiben nach dem Test zur Diagnose bestehen; nur der temporäre Prüfclient
wird entfernt. Ein fehlgeschlagener Vorabcheck kann abbrechen, bevor Ergebnisse
angelegt werden. Das Skript selbst ist bislang nur syntaktisch und in einzelnen
Hilfsfunktionen geprüft, **nicht am Docker-Stack validiert**.

## Szenarien und fehlende Messwerte

| Szenario | Messbare Erwartung | Aktueller Nachweis / noch fehlend |
|---|---|---|
| S1 | Root `mvn clean test`, beide Module, alle erforderlichen Tests ohne Fehler/Skip | 99 ausgewählte Tests erfolgreich; vollständiger Lauf offen |
| S2 | Frischer Vier-Dienste-Stack, Schema/PK/Index, keine Host-Ports, Consumer aktiv | Nur Konfiguration im Repository; Frischstart offen |
| S3 | 1'000 Antwort-IDs/Inhalte binnen 60 s ab letzter Annahme gespeichert, ready/unacknowledged null | Skript vorbereitet; IDs, Dauer und Ergebnis fehlen |
| S4 | Vor Start 1'000 ready, null unacknowledged/Consumer; danach rohe DB-Transaktionsdifferenz höchstens 100 und alle IDs gespeichert | Skript vorbereitet; Ausgangs-/Endwert und Differenz fehlen |
| S5 | Derselbe rohe Body zweimal, genau eine unveränderte Zeile, beide Lieferungen abgeschlossen, DLQ absolut leer | Integrationstest und Skript vorbereitet; echter Nachweis offen |
| S6 | Zwei Container/Consumer, 1'000 eindeutige gespeicherte IDs, Queue leer, keine neuen DLQ-Einträge | Integrationstests und Skript vorbereitet; Ausführung offen |
| S7 | PostgreSQL 15 s weg; 300 IDs binnen 90 s in DB/DLQ; beide unveränderten Writer danach wieder erfolgreich | Integrationstest und Skript vorbereitet; Ausführung und Messwerte offen |
| S8 | Keine Streams, Kommentare über allen Klassen/Methoden, verständlicher Code, keine Secrets | Automatische Teilprüfungen erfolgreich; vollständiges manuelles Review offen |

S4 pollt innerhalb des Messfensters ausschliesslich den Broker. Je zwei Sekunden
vor Baseline und Endwert dienen der Statistikveröffentlichung. Die rohe Differenz
wird nicht um geschätzte Nebenkosten reduziert. Die lokale 60-s-Abbruchgrenze ist
eine Diagnosegrenze und keine zusätzliche offizielle S4-Anforderung.

S7 startet PostgreSQL in einem unabhängigen PowerShell-Job nach 15 Sekunden;
verspäteter Neustart oder erst nach Wiederherstellung angenommene Testnachrichten
machen den Lauf ungültig. Die DLQ wird mit `ack_requeue_true` gelesen. Nach den
ursprünglichen 300 IDs müssen Kontrollgruppen innerhalb zehn Sekunden gespeichert
werden und beide Writer innerhalb insgesamt 30 Sekunden neue Commit-Logs zeigen.
Container-IDs, Startzeiten und Restart-Zähler werden verglichen.

Nach verfügbarem Docker zuerst die neuen Integrationstests und danach den gesamten
Durchlauf ausführen. Fehler zuerst erklären und gezielt korrigieren; erst nach
erneuter erfolgreicher Prüfung entsprechende N1–N19-Markierungen und Aufgaben
abhaken. README-Frischstart, Datenerhalt, echte Stop-Dauer sowie das manuelle S8-
Review bleiben zusätzlich nach der Spezifikation zu prüfen. **Kein Abgabe-Tag und
kein bestandener Gesamtnachweis werden mit diesem Zwischenstand behauptet.**
