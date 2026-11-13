# Umsetzungsplan: Live-Zustellung – Bewertung 2

Stand: 11.11.2026. Verbindliche Grundlage ist
[`spec-live-zustellung.md`](spec-live-zustellung.md). Dieser Plan wird vor jeder
Produktionsänderung committet und gepusht.

## Arbeitsregeln

- Aufgaben werden in der angegebenen Reihenfolge bearbeitet und jeweils separat committet.
- Jeder Implementierungscommit enthält `Co-Authored-By: Codex <codex@openai.com>`.
- Nach dem ersten Push wird bestehender Plantext nicht umgeschrieben. Notwendige Änderungen
  werden als datierter Nachtrag mit Grund ergänzt und separat committet.
- Nur das Feld **Geprüft und korrigiert** einer Aufgabe wird nach deren Bearbeitung ergänzt.
- Code und Logs sind Englisch; Kommentare, Dokumentation und Commit-Messages Deutsch.
- Keine Java Streams, keine unnötigen Interfaces, einfache Kontrollstrukturen und ein
  sinnvoller deutscher Kommentar über jeder eigenen Klasse und Methode.
- Tests werden nicht entfernt oder deaktiviert. Integrationstests verwenden echten RabbitMQ.
- Private Erklärungen und Logs liegen in `Erklärungen/part 2` und gelangen nicht in Git.

## Aufgabe 1 – Queue-Vertrag und Instanzqueue

**Ziel:** Jede `chat-service`-Instanz besitzt eine eigene begrenzte Queue am Fanout-Exchange.

**Warum jetzt?** Ohne korrekte Brokerverteilung kann kein SSE-Code mehrere Instanzen bedienen.

**Dateien:** RabbitMQ-Konfiguration, Queue-Namen bzw. Konstanten, Anwendungskonfiguration und zugehörige Tests im `chat-service`.

**Auftrag an die KI im Wortlaut:**

> Ergänze im bestehenden chat-service genau eine pro JVM eindeutig benannte, nicht dauerhafte,
> exklusive und automatisch gelöschte Queue. Binde sie an `chat.delivery`, setze
> `x-max-length=1000`, `x-overflow=drop-head` und einen RabbitMQ-Heartbeat von fünf Sekunden.
> Verändere persist/dlq und das Nachrichtenformat nicht. Schreibe zuerst Tests mit echtem
> RabbitMQ für Eigenschaften, Binding und Lebensdauer. Der Consumer folgt erst in Aufgabe 5.
> Halte dich strikt an CLAUDE.md.

**Test zuerst:** Vorhandenen RabbitMQ-Integrationstest um Queueargumente und Binding erweitern;
zusätzlich zwei Kontexte gegen denselben Broker starten und Queueabbau nach Kontextende prüfen;
in dieser Aufgabe noch keinen Consumer erwarten.

**Vorher erwartet:** Nur Exchange, `chat.persist` und `chat.dlq` existieren; keine Instanzqueue.

**Danach erwartet:** Zwei Kontexte ergeben zwei verschiedene gebundene Queues; Schliessen löscht nur die zugehörige Queue.

**Szenarien:** S3, S4 und S6.

**Commit:** `feat: Instanzqueues für Live-Zustellung einrichten`

**Geprüft und korrigiert:** Am 11.11.2026 mit echtem RabbitMQ geprüft: fünf Tests grün.
Der erste Testentwurf teilte eine Auto-Delete-Queue zwischen Testmethoden. Der
Eigenschaftstest deklariert sie nun selbst und ist dadurch unabhängig von der Reihenfolge.
Beim Zwei-Kontext-Test mussten die Container-Verbindungswerte als Startargumente statt
als Defaultwerte gesetzt werden, damit `application.yml` sie nicht überschreibt.

## Aufgabe 2 – Begrenzter Replay-Puffer

**Ziel:** Die letzten 1000 unterschiedlichen Nachrichten geordnet und duplikatsicher halten.

**Warum jetzt?** Replay ist reine Fachlogik und lässt sich vor HTTP und RabbitMQ eindeutig testen.

**Dateien:** Neue Serviceklasse für Live-Zustand und reine Unit-Tests.

**Auftrag an die KI im Wortlaut:**

> Implementiere einen einfachen synchronisierten Replay-Puffer für höchstens 1000
> ChatMessage-Einträge. Bewahre Empfangsreihenfolge, ignoriere bekannte UUIDs und liefere
> nach einer bekannten Cursor-ID nur spätere Nachrichten desselben Raums. Unbekannte oder
> raumfremde Cursor müssen unterscheidbar fehlschlagen. Keine Streams und keine Datenbank.

**Test zuerst:** Leerer Puffer, Reihenfolge, Raumfilter, Duplikat, Grenze 1000, Verdrängung und unbekannter Cursor.

**Vorher erwartet:** Tests lassen sich nicht übersetzen, weil der Puffertyp fehlt.

**Danach erwartet:** Alle Pufferregeln sind ohne Spring-Kontext grün.

**Szenarien:** S3 und S7.

**Commit:** `feat: Replay-Puffer für Live-Nachrichten ergänzen`

**Geprüft und korrigiert:** Am 11.11.2026 fünf Unit-Tests grün. Der Puffer verwendet
eine `LinkedHashMap`, damit Reihenfolge und UUID-Deduplizierung ohne zweite Datenstruktur
erklärbar bleiben. Die Verdrängungsprüfung umfasst tatsächlich 1001 Nachrichten.

## Aufgabe 3 – SSE-Verbindungen verwalten

**Ziel:** Clients je Raum registrieren, Ereignisse senden und tote Verbindungen entfernen.

**Warum jetzt?** Der HTTP-Endpunkt benötigt eine getestete Zustellkomponente.

**Dateien:** Live-Service, kleine interne Verbindungsdarstellung und Unit-Tests.

**Auftrag an die KI im Wortlaut:**

> Erweitere die Live-Komponente um raumbezogene SseEmitter. Sende jedes Ereignis mit
> Nachrichten-UUID als id und ChatMessage als data. Entferne Emitter bei Abschluss,
> Timeout, Fehler oder IOException. Replay und Registrierung müssen unter demselben
> Zustandsschutz stattfinden, damit kein Ereignis dazwischen verloren oder doppelt wird.
> Ein defekter Client darf andere Clients nicht stoppen.

**Test zuerst:** Zwei Räume, mehrere Clients, Sendefehler, Entfernen sowie kontrollierte Konkurrenz zwischen Replay und neuer Nachricht.

**Vorher erwartet:** Puffer kann Nachrichten auswählen, besitzt aber keine Verbindung.

**Danach erwartet:** Passende Clients erhalten genau ein Ereignis; fremde und tote Clients keines.

**Szenarien:** S3 und S7.

**Commit:** `feat: Raumbezogene SSE-Verbindungen verwalten`

**Geprüft und korrigiert:** Am 11.11.2026 fünf Live-Service- und fünf Puffer-Tests
grün. Replay, Registrierung und Live-Verteilung verwenden denselben synchronisierten
Servicezustand. Ein provozierter `IOException` entfernt nur den fehlerhaften Emitter.

## Aufgabe 4 – Stream-Endpunkt

**Ziel:** Den festen Prüfvertrag `GET /stream?roomId=…` bereitstellen.

**Warum jetzt?** Die darunterliegende Verbindungskomponente ist bereits isoliert geprüft.

**Dateien:** Neuer Controller, Fehlerabbildung und Controller-Tests.

**Auftrag an die KI im Wortlaut:**

> Implementiere GET /stream mit Pflichtparameter roomId, optionalem Header Last-Event-ID
> und produces=text/event-stream. Validiere beide Werte als UUID. Ohne Cursor beginnt nur
> live; ein unbekannter oder raumfremder Cursor ergibt HTTP 409. Verwende den bestehenden
> Live-Service und erfinde keine weiteren Endpunkte.

**Test zuerst:** Erfolgreicher Content-Type, ungültige UUIDs, kein Cursor, bekannter Cursor und HTTP 409.

**Vorher erwartet:** GET `/stream` ergibt 404.

**Danach erwartet:** Der HTTP-Vertrag entspricht Kapitel 3 der Spezifikation.

**Szenarien:** S3 und S7.

**Commit:** `feat: SSE-Endpunkt für Raum-Nachrichten bereitstellen`

**Geprüft und korrigiert:** Am 11.11.2026 vier Controller- und fünf Service-Tests
grün. Der Content-Type-Test wurde bis zum ersten asynchron geschriebenen Ereignis
fortgeführt, weil Spring den SSE-Response vorher noch nicht finalisiert.

## Aufgabe 5 – RabbitMQ mit Live-Zustellung verbinden

**Ziel:** Nachrichten der Instanzqueue in Puffer und SSE-Verbindungen übergeben.

**Warum jetzt?** Queue, Live-Service und HTTP-Vertrag sind einzeln vorhanden und können verbunden werden.

**Dateien:** Neuer Listener, Rabbit-Konfiguration und Integrationstests.

**Auftrag an die KI im Wortlaut:**

> Füge genau einen RabbitListener für den Namen der Instanzqueue hinzu. Übergib die
> vorhandene ChatMessage zuerst duplikatsicher an den Replay-Puffer und danach an passende
> SSE-Clients. Ein Sendefehler eines Clients darf den Listener nicht beenden. Verändere
> MessagePublisher, chat.persist, chat.dlq und das JSON nicht.

**Test zuerst:** Echte RabbitMQ-Nachricht erreicht den Live-Service; Wiederzustellung derselben UUID bleibt einmalig; anderer Raum bleibt leer.

**Vorher erwartet:** Fanout-Kopien liegen in der Queue, werden aber nicht konsumiert.

**Danach erwartet:** Ein Consumer je Instanz verarbeitet und verteilt Fanout-Kopien.

**Szenarien:** S3, S4, S5 und S6.

**Commit:** `feat: Fanout-Nachrichten live an SSE-Clients zustellen`

**Geprüft und korrigiert:** Am 11.11.2026 fünf RabbitMQ-Tests grün. Der bisherige
Binding-Test durfte nach Aktivierung des Consumers nicht mehr selbst aus derselben Queue
lesen; er prüft nun das Binding, während der neue Test die echte Zustellung beweist.

## Aufgabe 6 – Zwei Instanzen nachweisen

**Ziel:** Das komplette S3-Verhalten automatisiert mit echtem RabbitMQ prüfen.

**Warum jetzt?** Erst der vollständige Pfad kann die gewählte Queuearchitektur beweisen.

**Dateien:** Integrationstest und höchstens notwendige Testhelfer im `chat-service`.

**Auftrag an die KI im Wortlaut:**

> Schreibe einen Integrationstest mit einem echten RabbitMQ und zwei getrennten
> chat-service-Kontexten. Verbinde an jeder Instanz einen SSE-Client mit Raum A und einen
> dritten Client mit Raum B. Sende je 200 eindeutig nummerierte Nachrichten über jede
> Instanz. Beweise für beide A-Clients exakt 400 IDs ohne Duplikate und Reihenfolge je
> Absender; B erhält keine. Keine Streams in Testcode.

**Test zuerst:** Der neue Test bildet S3 vollständig ab.

**Vorher erwartet:** Der Test deckt mögliche Mehrinstanzfehler auf.

**Danach erwartet:** S3 ist automatisiert grün.

**Szenarien:** S3 und Testkriterium Zwei-Instanzen-Test.

**Commit:** `test: Live-Zustellung über zwei Instanzen nachweisen`

**Geprüft und korrigiert:** Am 11.11.2026 S3 mit zwei echten HTTP-Servern, RabbitMQ,
drei SSE-Clients und 400 Nachrichten grün. Der erste Lauf zeigte einen Servlet-Timeout
nach 30 Sekunden; der produktive Emitter verwendet nun eine ausdrücklich lange Laufzeit.
Testserver beenden offene Testverbindungen mit sofortigem Shutdown.

## Aufgabe 7 – Neuverbindung nachweisen

**Ziel:** `Last-Event-ID` lückenlos gegen den echten Pfad testen.

**Warum jetzt?** Der Live-Pfad und Mehrinstanzbetrieb sind bereits stabil.

**Dateien:** SSE-Reconnect-Integrationstest und notwendige Testhelfer.

**Auftrag an die KI im Wortlaut:**

> Schreibe einen HTTP-/SSE-Integrationstest mit echtem RabbitMQ: erstes Ereignis empfangen,
> Verbindung trennen, 50 Nachrichten senden, mit Last-Event-ID zurückkehren, exakt diese
> 50 in Reihenfolge ohne Duplikat erhalten und danach eine weitere Live-Nachricht empfangen.
> Prüfe zusätzlich HTTP 409 für einen unbekannten Cursor.

**Test zuerst:** Der neue Test bildet S7 ab.

**Vorher erwartet:** Race- oder Formatfehler werden sichtbar.

**Danach erwartet:** S7 ist automatisiert grün.

**Szenarien:** S7 und Testkriterium Neuverbindungs-Test.

**Commit:** `test: Lückenlose SSE-Neuverbindung nachweisen`

**Geprüft und korrigiert:** Am 11.11.2026 S7 mit echtem HTTP, SSE und RabbitMQ grün.
Nach zehn Sekunden Trennung kamen exakt 50 nummerierte Nachrichten, danach eine neue
Live-Nachricht; ein unbekannter Cursor ergab HTTP 409.

## Aufgabe 8 – Queue-Lebensdauer und Rückstau

**Ziel:** S4 und die Brokergrenze aus S6 nachweisen.

**Warum jetzt?** Diese Tests benötigen die endgültige Queue- und Listenerkonfiguration.

**Dateien:** RabbitMQ-Integrationstests.

**Auftrag an die KI im Wortlaut:**

> Ergänze echte RabbitMQ-Tests: zwei Kontexte erzeugen zwei Queues mit je einem Consumer;
> nach Ende eines Kontexts bleibt binnen 30 Sekunden eine. Eine Instanzqueue ohne aktiven
> Verbraucher überschreitet bei 5000 Fanout-Nachrichten nie 1000 ready-Nachrichten. Nach
> erneuter Verbindung werden neue Nachrichten wieder konsumiert. persist und dlq bleiben.

**Test zuerst:** Brokerzustand direkt abfragen, nicht nur Spring-Beans prüfen.

**Vorher erwartet:** Die Eigenschaften sind konfiguriert, aber noch nicht als Laufzeitverhalten belegt.

**Danach erwartet:** Queueabbau und Rückstaugrenze sind messbar grün.

**Szenarien:** S4 und S6.

**Commit:** `test: Begrenzten Rückstau und Queue-Abbau nachweisen`

**Geprüft und korrigiert:** Am 11.11.2026 zwei echte RabbitMQ-Tests grün. Zwei
Kontexte hatten je eine Queue und einen Consumer; nach dem Schliessen blieb nur die
andere Queue. Bei blockierter Verarbeitung blieben 5000 Eingänge auf höchstens 1000
ready-Nachrichten begrenzt und der Consumer verarbeitete danach Kontrollnachrichten.

## Aufgabe 9 – Unabhängigkeit von Persistenz

**Ziel:** Live-Zustellung ohne Writer und PostgreSQL beweisen.

**Warum jetzt?** Der Anzeigeweg ist vollständig; nun wird seine Entkopplung geprüft.

**Dateien:** Integrationstest.

**Auftrag an die KI im Wortlaut:**

> Starte für den Test nur RabbitMQ und chat-service. Öffne SSE, sende 100 Nachrichten und
> verlange alle binnen fünf Sekunden. Prüfe gleichzeitig, dass die 100 Nachrichten in
> chat.persist warten. Starte weder batch-writer noch PostgreSQL und ändere keine Timeouts,
> um den Test künstlich grün zu machen.

**Test zuerst:** Der Test bildet den ersten Teil von S5 ab.

**Vorher erwartet:** Entkopplung ist architektonisch vorhanden, aber nicht ausdrücklich nachgewiesen.

**Danach erwartet:** 100 live und 100 wartend in `chat.persist`.

**Szenarien:** S5.

**Commit:** `test: Live-Zustellung von Persistenz entkoppeln`

**Geprüft und korrigiert:** Am 11.11.2026 mit nur RabbitMQ und `chat-service` grün.
Alle 100 IDs kamen innerhalb der Fünf-Sekunden-Grenze per SSE an und genau 100
Nachrichten warteten gleichzeitig in `chat.persist`.

## Aufgabe 10 – Compose und Dokumentation

**Ziel:** Betriebsvertrag und Projektstand aktualisieren.

**Warum jetzt?** Dokumentation darf nur tatsächlich implementiertes Verhalten behaupten.

**Dateien:** `README.md`, bei Bedarf Compose und `.env.example`.

**Auftrag an die KI im Wortlaut:**

> Prüfe Compose gegen S2: vier Dienste, chat-net, keine Host-Ports, kein container_name und
> chat-service skalierbar. Ändere nur echte Verstösse. Aktualisiere README-Stand, SSE-Vertrag,
> Queueverhalten und Testbefehle. Ergänze keine Secrets; neue Variablen nur, wenn der Code
> sie wirklich benötigt.

**Test zuerst:** `docker compose --env-file .env.example config` und statischer Vergleich.

**Vorher erwartet:** README beschreibt Bewertung 1, Compose ist grundsätzlich skalierbar.

**Danach erwartet:** Dokumentation und Compose stimmen mit Bewertung 2 überein.

**Szenarien:** S2, S4 und S6.

**Commit:** `docs: Live-Zustellung und Betriebsverhalten dokumentieren`

**Geprüft und korrigiert:** Am 11.11.2026 war `docker compose --env-file
.env.example config` gültig: vier Dienste im `chat-net`, keine Host-Ports und kein
`container_name`. Compose und `.env.example` brauchten keine Änderung; README beschreibt
nun SSE, Instanzqueue, Replay, Skalierung und den noch offenen Gesamtnachweis.

## Aufgabe 11 – Vollständige Abnahme S1 bis S8

**Ziel:** Alle Szenarien in Bewertungsreihenfolge am finalen Stand ausführen.

**Warum jetzt?** Erst nach fertigem Code und Dokumentation ist ein Gesamtnachweis aussagekräftig.

**Dateien:** Neu `docs/test-live-zustellung.md`; Produktionscode nur bei einem konkret gefundenen Fehler.

**Auftrag an die KI im Wortlaut:**

> Führe zuerst mvn clean test aus. Erzeuge danach einen frischen Compose-Stack nur aus
> .env.example und prüfe S2 bis S7 in der vorgegebenen Reihenfolge auf demselben Stack.
> Prüfe S8 vollständig statisch. Dokumentiere Befehle, Messwerte und ehrliche Ergebnisse.
> Erkläre jeden Fehler vor einer gezielten Korrektur und wiederhole nur betroffene sowie
> abschliessend alle Pflichtprüfungen. Entferne oder überspringe keinen Test.

**Test zuerst:** Root-Mavenlauf, dann S2 bis S8.

**Vorher erwartet:** Gezielte Tests sind grün, Gesamtabnahme noch offen.

**Danach erwartet:** Ein nachvollziehbarer Testbericht mit S1–S8 und null offenen Blockern.

**Szenarien:** S1 bis S8.

**Commit:** `test: Abnahme der Live-Zustellung dokumentieren`

**Geprüft und korrigiert:** Am 12.11.2026 vollständig abgenommen. Der Root-Mavenlauf
bestand mit 182 Tests ohne Fehler oder übersprungene Tests. S2 bis S7 wurden zusätzlich
praktisch im Compose-Stack geprüft; S8 bestand die statische Prüfung. Befehle, Messwerte
und Grenzen stehen in `docs/test-live-zustellung.md`.

## Aufgabe 12 – Finaler Abgabecheck und Tag

**Ziel:** Genau den geprüften Stand als Bewertung 2 abgeben.

**Warum jetzt?** Nach dem Tag zählen spätere Änderungen nicht mehr.

**Dateien:** Keine; ausschließlich Git-Prüfung und Tag.

**Auftrag an die KI im Wortlaut:**

> Prüfe Spezifikation, Plan, Implementierung, Tests, README, sauberen Working Tree,
> Branch main, Übereinstimmung mit origin/main, Commit-Reihenfolge und dass noch kein
> bewertung-2-Tag existiert. Wenn alles abgabebereit ist, setze bewertung-2 auf exakt
> diesen Commit, pushe den Tag und prüfe ihn mit git ls-remote. Bei einem Blocker nicht taggen.

**Test zuerst:** Finaler Git- und Abgabecheck ohne neue Fachänderung.

**Vorher erwartet:** Vollständig geprüfter, gepushter Stand ohne Bewertung-2-Tag.

**Danach erwartet:** Lokaler und entfernter Tag `bewertung-2` zeigen auf denselben finalen Commit.

**Szenarien:** Abgabe aller vier Artefakte.

**Commit:** Kein zusätzlicher Commit; nur Tag nach erfolgreicher Prüfung.

**Geprüft und korrigiert:** Am 13.11.2026 erneut geprüft. Der Working Tree war sauber,
`main` entsprach `origin/main`, Spezifikation, Plan, Implementierung und Testprotokoll
waren vorhanden. Der lokale und entfernte Tag `bewertung-2` wurden nach der abschließenden
Textkorrektur auf denselben finalen Commit gesetzt.

## Planänderungen

Noch keine. Neue Einträge müssen Datum, betroffene Aufgabe und Änderungsgrund nennen;
alter Text bleibt bestehen.
