# PLANUNG – Chat-App

## 1. Ziel und Rahmenbedingungen

Ziel ist die Planung einer webbasierten Chat-App als Microservice-Architektur.

Vorgegebene Rahmenbedingungen:

- Java 21 für das Backend.
- Keycloak als zentraler Login- und Identity-Dienst.
- Die gesamte Architektur muss mit `docker-compose` startbar sein.
- Alle internen Services kommunizieren über ein Docker-internes Netzwerk.
- Nur die Web-App darf über `localhost` vom Host aus erreichbar sein.
- Datenbank, Message Broker, Keycloak und Backend-Services erhalten keine direkt veröffentlichten Host-Ports.
- Die Architektur soll zunächst als überschaubares MVP umgesetzt werden und später erweiterbar bleiben.

Der zentrale Kommunikationsweg lautet:

```text
Browser
   |
   | localhost
   v
Web-App
   |
   | internes Docker-Netzwerk
   v
Backend / Keycloak / RabbitMQ / Datenbanken
```

---

## 2. Gewählter Stack

### 2.1 Backend: Java 21 + Spring Boot

Java 21 liefert Sprache und Laufzeit, aber noch kein vollständiges Web-Backend. Ohne Framework müssten HTTP-Server, Routing, JSON-Verarbeitung, Security, Datenbankzugriff und Konfiguration größtenteils selbst aufgebaut werden.

Spring Boot stellt dafür eine fertige Infrastruktur bereit.

Geplante Spring-Komponenten:

- Spring Web für REST-Endpunkte.
- Spring Security für Authentifizierung und Autorisierung.
- OAuth2 Resource Server / OpenID Connect für Keycloak.
- Spring Data JPA für PostgreSQL.
- Spring AMQP für RabbitMQ.
- Spring Boot Actuator für Health Checks und Monitoring.

Beispiel:

```java
@RestController
@RequestMapping("/api/chats")
public class ChatController {

    @GetMapping
    public List<Chat> getChats() {
        return chatService.getChats();
    }
}
```

Spring Boot übernimmt dabei unter anderem HTTP-Verarbeitung, JSON-Konvertierung, Dependency Injection und Konfiguration.

#### Alternativen zu Spring Boot

**Quarkus**

Stärken:

- sehr schneller Start,
- geringer Speicherverbrauch,
- gut für Container und Cloud,
- gute GraalVM-Unterstützung.

Schwächen:

- kleineres Ökosystem als Spring,
- weniger verbreitet,
- weniger Lernmaterial.

**Micronaut**

Stärken:

- geringer Ressourcenverbrauch,
- schneller Start,
- Dependency Injection zur Compile-Zeit,
- gut für Microservices.

Schwächen:

- kleineres Ökosystem,
- weniger Beispiele als bei Spring.

**Entscheidung:** Für dieses Projekt wird Spring Boot gewählt, weil Dokumentation, Community und Integrationen mit Keycloak, RabbitMQ und PostgreSQL besonders stark sind.

---

### 2.2 Frontend

Für den ersten Stand wird eine Web-App vorgesehen.

Gewählte Variante:

- React,
- TypeScript,
- Nginx oder vergleichbarer Webserver als statischer Webserver und Reverse Proxy.

Die Web-App ist der einzige öffentlich erreichbare Einstiegspunkt.

```text
http://localhost:8080
```

Backend-Aufrufe werden intern weitergeleitet:

```text
Browser
   |
   | GET /api/chats
   v
Web-App / Reverse Proxy
   |
   | http://chat-backend:8080/api/chats
   v
Chat-Backend
```

Eine Desktop-App wird für das MVP nicht umgesetzt. Später könnte sie dieselben Backend-APIs verwenden.

---

## 3. Authentifizierung mit Keycloak

Keycloak ist gemäß Aufgabenstellung der zentrale Login-Dienst.

Keycloak übernimmt:

- Benutzerverwaltung,
- Passwörter,
- Login,
- Rollen,
- Gruppen,
- Sessions,
- Token-Ausgabe,
- OpenID Connect,
- OAuth 2.0.

Das Chat-Backend implementiert keine eigene Passwortverwaltung.

### Login-Ablauf

```text
Browser
   |
   | Login
   v
Keycloak
   |
   | Benutzername + Passwort
   v
Authentifizierung
   |
   | Token
   v
Web-App / Browser
```

Bei geschützten Backend-Aufrufen wird ein Access Token mitgeschickt:

```http
Authorization: Bearer <access-token>
```

Das Backend prüft unter anderem:

- Signatur,
- Gültigkeit,
- Aussteller,
- Zielgruppe,
- Benutzer-ID,
- Rollen.

Für die Web-App ist vorgesehen:

- OAuth 2.0,
- OpenID Connect,
- Authorization Code Flow,
- PKCE.

PKCE schützt browserbasierte Anwendungen zusätzlich gegen den Missbrauch abgefangener Authorization Codes.

---

## 4. Datenbank

### Gewählte Datenbank: PostgreSQL

Die Chat-App hat viele strukturierte Beziehungen:

```text
User
  |
  +---- ChatMember ---- Chat
                         |
                         +---- Message
```

Ein Benutzer kann in mehreren Chats sein. Ein Chat kann mehrere Mitglieder haben und besitzt viele Nachrichten. Das passt gut zu einem relationalen Datenmodell.

Beispiel:

```text
chat
-------------------------
id
name
created_at

chat_member
-------------------------
chat_id
user_id

message
-------------------------
id
chat_id
sender_id
text
created_at
```

PostgreSQL bietet:

- ACID-Transaktionen,
- Foreign Keys,
- Constraints,
- Joins,
- starke SQL-Unterstützung,
- Indizes,
- JSONB,
- hohe Stabilität.

### Alternativen

**MySQL / MariaDB**

Stärken:

- sehr weit verbreitet,
- stabil,
- gute Performance,
- große Community.

Schwächen gegenüber PostgreSQL:

- PostgreSQL bietet oft umfangreichere SQL-Funktionen,
- PostgreSQL ist bei komplexen Datentypen und Abfragen flexibler.

Für dieses Projekt wären MySQL oder MariaDB grundsätzlich ebenfalls geeignet.

**MongoDB**

Stärken:

- flexibel bei uneinheitlichen Datenstrukturen,
- dokumentenorientiert,
- kein starres relationales Schema erforderlich.

Schwächen:

- Beziehungen zwischen Chats, Benutzern und Mitgliedschaften sind relational meist einfacher,
- Integritätsregeln liegen häufiger in der Anwendung.

**Redis**

Stärken:

- sehr schnell,
- gut für Cache,
- Sessions,
- Online-Status,
- Presence,
- Rate Limiting.

Schwäche:

- nicht als primäre dauerhafte Chat-Datenbank vorgesehen.

Später wäre folgende Kombination denkbar:

```text
PostgreSQL
   |
   +-- dauerhafte Nachrichten und Chats

Redis
   |
   +-- Cache
   +-- Online-Status
```

**Entscheidung:** PostgreSQL wird als primäre Datenbank verwendet.

---

## 5. Datenbankmigrationen

### Gewähltes Tool: Flyway

Während der Entwicklung verändert sich das Datenbankschema.

Beispiel:

```text
V1__create_chat.sql
V2__create_message.sql
V3__add_sender.sql
V4__add_message_status.sql
```

Flyway speichert, welche Migrationen bereits ausgeführt wurden. Dadurch bleibt nachvollziehbar, welche Datenbankversion aktuell ist und welche Änderungen in welcher Reihenfolge erfolgt sind.

### Alternativen

**Liquibase**

Stärken:

- sehr mächtig,
- gute Rollback-Unterstützung,
- unterstützt XML, YAML, JSON und SQL,
- gut für komplexe Enterprise-Projekte.

Schwächen:

- umfangreicher,
- mehr Konfiguration,
- für kleine Projekte schwergewichtiger.

**Hibernate `ddl-auto`**

Beispiel:

```properties
spring.jpa.hibernate.ddl-auto=update
```

Stärken:

- bequem in frühen Entwicklungsphasen,
- Tabellen können automatisch aus Entities erzeugt werden.

Schwächen:

- wenig Kontrolle über konkrete Migrationen,
- Änderungen sind schwerer nachvollziehbar,
- kein vollwertiger Ersatz für versionierte Migrationen.

**Manuelle SQL-Skripte**

Stärken:

- maximale Kontrolle,
- keine zusätzliche Abhängigkeit.

Schwächen:

- keine automatische Versionsverwaltung,
- schwieriger nachzuvollziehen, was bereits ausgeführt wurde,
- höhere Fehlergefahr zwischen verschiedenen Umgebungen.

**Entscheidung:** Flyway wird verwendet, weil es einfach, SQL-nah und sehr gut in Spring Boot integrierbar ist.

---

## 6. Messaging und asynchrone Kommunikation

### Gewählter Message Broker: RabbitMQ

RabbitMQ dient nicht als Datenbank, sondern zur asynchronen Kommunikation zwischen Services.

```text
Chat-Backend
     |
     | message.created
     v
  RabbitMQ
     |
     +----> Notification-Service
     |
     +----> Audit-Service
     |
     +----> weitere Consumer
```

Typische Events:

- `message.created`,
- `message.deleted`,
- `user.joined`,
- Benachrichtigungen,
- Audit-Ereignisse,
- Hintergrundverarbeitung.

Stärken von RabbitMQ:

- klassische Message Queues,
- Exchanges und Routing,
- Acknowledgements,
- Retry-Mechanismen,
- Dead Letter Queues,
- gute Spring-Unterstützung,
- überschaubare Komplexität.

### Alternativen zu RabbitMQ

**Apache Kafka**

Stärken:

- sehr hoher Durchsatz,
- Event Streaming,
- Event Replay,
- große Datenmengen,
- viele Consumer,
- Analytics.

Schwächen:

- höhere Komplexität,
- Topics, Partitionen, Consumer Groups, Offsets und Retention müssen verstanden und verwaltet werden,
- für einen kleinen Chat-MVP wahrscheinlich unnötig schwergewichtig.

**NATS**

Stärken:

- sehr schnell,
- geringer Ressourcenverbrauch,
- einfache Architektur,
- gut für Microservice-Kommunikation,
- mit JetStream auch persistentes Messaging.

Schwächen:

- kleineres Ökosystem,
- RabbitMQ ist für klassische Queue- und Routing-Szenarien sehr ausgereift.

**Redis Streams**

Stärken:

- sinnvoll, wenn Redis ohnehin eingesetzt wird,
- weniger unterschiedliche Infrastrukturkomponenten,
- relativ einfach.

Schwächen:

- Redis ist nicht primär als spezialisierter Message Broker entstanden,
- RabbitMQ bietet umfangreichere Messaging-Funktionen.

**ActiveMQ Artemis**

Stärken:

- starke JMS-Unterstützung,
- passend für klassische Java-/Enterprise-Systeme,
- bewährte Messaging-Funktionen.

Schwächen:

- kleineres modernes Microservice-Ökosystem als RabbitMQ oder Kafka.

**AWS SQS / Azure Service Bus**

Stärken:

- vollständig gemanagt,
- kein eigener Broker-Betrieb,
- hohe Verfügbarkeit durch den Cloud-Anbieter.

Schwächen:

- Cloud-Abhängigkeit,
- schlechter passend zur vollständig lokal mit Docker Compose abbildbaren Aufgabenstellung.

**Entscheidung:** RabbitMQ wird verwendet, weil es für klassische asynchrone Microservice-Kommunikation ausreichend mächtig ist, ohne die zusätzliche Komplexität von Kafka einzuführen.

---

## 7. Synchron vs. asynchron

### Synchron

Der Client wartet direkt auf eine Antwort.

```text
Browser
   |
   | GET /api/chats
   v
Backend
   |
   | JSON
   v
Browser
```

Geeignet für:

- Chats laden,
- Chat öffnen,
- Nachrichtenhistorie laden,
- Benutzerprofil abrufen.

### Asynchron

Ein Ereignis wird veröffentlicht und andere Komponenten reagieren darauf.

```text
Backend
   |
   | message.created
   v
RabbitMQ
   |
   +----> Consumer A
   +----> Consumer B
```

RabbitMQ ersetzt daher nicht die normale REST-Kommunikation.

---

## 8. Realtime-Kommunikation

### Variante 1: Polling

Der Browser fragt regelmäßig nach neuen Nachrichten:

```text
Browser
   |
   | alle 2 Sekunden
   v
GET /api/chats/123/messages
```

Stärken:

- sehr einfach,
- leicht zu testen,
- basiert auf normalem HTTP,
- gut für einen frühen MVP.

Schwächen:

- unnötige Requests ohne neue Nachrichten,
- Verzögerung bis zum nächsten Polling-Intervall,
- schlechter skalierbar bei sehr vielen Benutzern.

### Variante 2: WebSocket

Eine bidirektionale Verbindung bleibt dauerhaft offen:

```text
Browser
   |====================|
   |    WebSocket       |
   |====================|
Backend
```

Stärken:

- echte Realtime-Kommunikation,
- Server kann sofort Daten an den Client schicken,
- bidirektional,
- sehr gut für Chat-Anwendungen.

Schwächen:

- komplexer als REST,
- Reconnect-Logik erforderlich,
- Verbindungsmanagement nötig,
- Skalierung über mehrere Backend-Instanzen wird komplexer.

### Variante 3: Server-Sent Events (SSE)

SSE hält eine HTTP-Verbindung offen, über die der Server Events zum Browser schickt.

Stärken:

- einfacher als WebSocket,
- gut für Server-zu-Client-Updates,
- basiert auf HTTP.

Schwächen:

- primär unidirektional,
- für vollständige bidirektionale Chat-Kommunikation weniger flexibel.

### Entscheidung

Für den ersten MVP wird **REST + Polling** verwendet. Damit werden zuerst Login, REST-API, Datenbank, Persistenz und Berechtigungen stabil umgesetzt.

Danach wird **WebSocket** ergänzt.

Die Geschäftslogik wird vom Transport getrennt:

```text
                 ChatService
                    |
             +------+------+
             |             |
             v             v
      REST Controller   WebSocket
```

Geplanter Ablauf:

```text
REST
  ↓
Nachrichten speichern
  ↓
Nachrichten laden
  ↓
Polling
  ↓
MVP vollständig testen
  ↓
WebSocket ergänzen
  ↓
Polling für Realtime-Nachrichten entfernen
```

---

## 9. Vorgeschlagene Services

### `web-app`

Aufgaben:

- React-Anwendung ausliefern,
- Reverse Proxy für `/api/*`,
- zentraler Einstiegspunkt,
- einziger Service mit Host-Port.

### `chat-backend`

Technologie:

- Java 21,
- Spring Boot.

Aufgaben:

- Chat-REST-API,
- Geschäftslogik,
- Authentifizierung und Autorisierung,
- Datenbankzugriff,
- RabbitMQ-Events,
- später WebSocket.

### `rabbitmq`

Aufgaben:

- asynchrones Messaging,
- Queueing,
- Event-Verteilung.

### `postgres`

Aufgaben:

- dauerhafte Speicherung der Chat-Daten.

### `keycloak`

Aufgaben:

- Login,
- Benutzer,
- Rollen,
- Token,
- OpenID Connect.

### `keycloak-db`

Aufgaben:

- persistente Daten von Keycloak.

Die Keycloak-Daten werden bewusst von den eigentlichen Chat-Daten getrennt.

---

## 10. Docker-Compose-Architektur

Geplante Services:

```text
services:
  web-app
  chat-backend
  rabbitmq
  postgres
  keycloak
  keycloak-db
```

Alle Services befinden sich in einem gemeinsamen Docker-Netzwerk:

```yaml
networks:
  chat-net:
    driver: bridge
```

Die Services sprechen sich über ihre Docker-Service-Namen an:

```text
chat-backend:8080
postgres:5432
rabbitmq:5672
keycloak:8080
keycloak-db:5432
```

Dadurch müssen keine festen Container-IP-Adressen hinterlegt werden.

---

## 11. Nur die Web-App über localhost

Nur die Web-App veröffentlicht einen Host-Port:

```yaml
web-app:
  ports:
    - "8080:80"
```

Die übrigen Services erhalten keine `ports:`-Einträge.

```yaml
chat-backend:
  # kein ports:

postgres:
  # kein ports:

rabbitmq:
  # kein ports:

keycloak:
  # kein ports:
```

Damit gilt:

```text
Browser
   |
   | localhost:8080
   v
Web-App
   |
   | Docker-intern
   +----> Backend
   +----> Keycloak
```

Nicht vorgesehen:

```text
Browser ---> localhost:8081 ---> Backend
Browser ---> localhost:5432 ---> PostgreSQL
Browser ---> localhost:5672 ---> RabbitMQ
```

---

## 12. Architekturübersicht

```text
                         HOST
                          |
                     localhost:8080
                          |
                          v
               +----------------------+
               |       WEB-APP        |
               | React + TypeScript   |
               | Reverse Proxy        |
               +----------+-----------+
                          |
========================================================
             internes Docker-Netzwerk
========================================================
                          |
             +------------+------------+
             |                         |
             v                         v
      +--------------+           +-------------+
      | Chat-Backend |           |  Keycloak   |
      | Java 21      |           | OIDC/OAuth2 |
      | Spring Boot  |           +------+------+
      +------+-------+                  |
             |                          v
       +-----+------+              +-----------+
       |            |              | Keycloak  |
       v            v              | DB        |
+------------+  +----------+       +-----------+
| PostgreSQL |  | RabbitMQ |
| Chat-Daten |  | Events   |
+------------+  +----------+
```

---

## 13. Beispielabläufe

### Login

1. Benutzer öffnet die Web-App.
2. Die Anwendung startet den Login über Keycloak.
3. Keycloak authentifiziert den Benutzer.
4. Die Anwendung erhält ein Token.
5. Der Benutzer ruft einen geschützten Backend-Endpunkt auf.
6. Das Backend validiert das Token.
7. Benutzer-ID und Rollen werden aus den Claims übernommen.

Das Passwort wird ausschließlich von Keycloak verarbeitet.

### Nachricht senden

```text
Browser
   |
   | POST /api/chats/123/messages
   v
Web-App / Reverse Proxy
   |
   v
Chat-Backend
   |
   +----> Token prüfen
   |
   +----> Nachricht validieren
   |
   +----> PostgreSQL
   |
   +----> RabbitMQ: message.created
```

PostgreSQL speichert die Nachricht dauerhaft. RabbitMQ verteilt das Ereignis.

---

## 14. Sicherheit

Geplante Sicherheitsregeln:

- keine eigene Passwortspeicherung im Chat-Backend,
- Keycloak als zentrale Identity-Instanz,
- geschützte Backend-Endpunkte,
- Rollen und Berechtigungen über Token-Claims,
- keine veröffentlichten Datenbankports,
- kein veröffentlichter RabbitMQ-Port,
- kein veröffentlichter Backend-Port,
- Secrets nicht im Quellcode,
- Eingabevalidierung,
- HTTPS für spätere produktive Umgebungen.

Für lokale Entwicklung können Secrets zunächst über `.env` eingebunden werden.

---

## 15. Persistente Docker-Daten

Geplante Docker Volumes:

```text
postgres-data
keycloak-db-data
rabbitmq-data
```

Damit bleiben Daten auch nach Container-Neustarts erhalten.

---

## 16. Projektstruktur

Eine mögliche Struktur:

```text
chat-app/
├── docker-compose.yml
├── .env.example
├── PLANUNG.md
│
├── web-app/
│   ├── src/
│   ├── package.json
│   └── Dockerfile
│
├── chat-backend/
│   ├── src/
│   ├── pom.xml
│   └── Dockerfile
│
└── config/
    └── keycloak/
```

---

## 17. MVP

Der erste funktionsfähige Stand enthält:

- Login über Keycloak,
- Web-App,
- Chatübersicht,
- Chat öffnen,
- Nachricht senden,
- Nachricht persistieren,
- Nachrichten laden,
- Autorisierung,
- REST-API,
- zunächst Polling,
- RabbitMQ für erste asynchrone Events,
- PostgreSQL,
- vollständiger Start über Docker Compose,
- nur Web-App über localhost erreichbar.

---

## 18. Spätere Erweiterungen

Mögliche Erweiterungen:

- WebSocket statt Polling,
- Gruppen-Chats,
- Nachrichten bearbeiten,
- Nachrichten löschen,
- Lesebestätigungen,
- Typing-Indikator,
- Presence / Online-Status,
- Notification-Service,
- Redis für Cache oder Presence,
- Datei-Uploads,
- Desktop-App,
- mobile Clients,
- Such-Service,
- horizontale Skalierung.

---

## 19. Offene Punkte

1. Maven oder Gradle als Java-Build-System?
2. Nur 1:1-Chats oder auch Gruppen-Chats?
3. Können Nachrichten bearbeitet werden?
4. Können Nachrichten gelöscht werden?
5. Werden Lesebestätigungen benötigt?
6. Welche Keycloak-Rollen werden benötigt?
7. Wird zusätzlich zu Keycloak ein lokales Benutzerprofil gespeichert?
8. Welche RabbitMQ Exchanges, Queues und Routing Keys werden benötigt?
9. Welches Polling-Intervall wird im MVP verwendet?
10. Ab welchem Entwicklungsstand wird auf WebSocket umgestellt?
11. Wird für die Web-App Nginx als Reverse Proxy eingesetzt?
12. Wie werden Secrets lokal verwaltet?
13. Welche Unit-, Integrations- und End-to-End-Tests werden benötigt?
14. Welche Logging- und Monitoring-Funktionen werden benötigt?
15. Soll später Redis für Cache oder Online-Status ergänzt werden?
16. Wie wird mit RabbitMQ-Fehlern und Dead Letter Queues umgegangen?
17. Werden gelöschte Nachrichten wirklich gelöscht oder nur als gelöscht markiert?

---

## 20. Getroffene Architekturentscheidungen

### Java 21 + Spring Boot

Spring Boot wird gewählt, weil es die benötigte Web-, Security-, Datenbank- und Messaging-Infrastruktur bereitstellt. Quarkus und Micronaut wurden betrachtet, aber wegen des größeren Spring-Ökosystems nicht bevorzugt.

### PostgreSQL

PostgreSQL wird als primäre Datenbank eingesetzt. MySQL/MariaDB wären möglich, MongoDB wurde wegen des stärker relationalen Datenmodells nicht bevorzugt. Redis ist eher als spätere Ergänzung für Cache oder Presence vorgesehen.

### RabbitMQ

RabbitMQ wird als Message Broker verwendet. Kafka wurde für den MVP als zu komplex eingestuft. NATS, Redis Streams und ActiveMQ Artemis wurden betrachtet, bieten für den aktuellen Anwendungsfall aber keinen entscheidenden Vorteil.

### Flyway

Flyway wird für Datenbankmigrationen verwendet. Liquibase ist mächtiger, aber schwergewichtiger. Hibernate `ddl-auto` und ausschließlich manuelle Skripte werden nicht als produktive Migrationsstrategie verwendet.

### Polling und WebSocket

Für den ersten MVP wird Polling eingesetzt. Sobald Login, REST, Persistenz und Berechtigungen stabil funktionieren, wird WebSocket für Realtime-Kommunikation ergänzt.

---

## Verlauf

Die Planung entstand schrittweise gemeinsam mit der KI.

### Ausgangslage

Zu Beginn standen folgende Anforderungen fest:

- Java 21,
- Web-Oberfläche,
- Login,
- Message Queue,
- Backend,
- Identity Provider,
- Docker Compose,
- internes Docker-Netzwerk,
- nur Web-App über localhost erreichbar.

Der ursprünglich allgemein skizzierte Identity Provider wurde durch die Aufgabenstellung konkret als **Keycloak** festgelegt.

### Fragen und Klärungen

Die KI hat in der ersten Planung offene Fragen festgehalten, die für die weitere Umsetzung noch beantwortet werden müssen. Dazu gehören insbesondere:

- 1:1-Chat oder Gruppen-Chat?
- WebSocket, SSE oder Polling?
- Maven oder Gradle?
- Welche Rollen werden in Keycloak benötigt?
- Wird zusätzlich ein lokales Benutzerprofil benötigt?
- Welche Nachrichtenfunktionen wie Bearbeiten, Löschen oder Lesebestätigung werden benötigt?
- Welche genaue RabbitMQ-Struktur wird verwendet?

Im bisherigen Gespräch wurden diese Punkte noch nicht alle endgültig beantwortet.

Der Benutzer stellte anschließend Rückfragen zu den vorgeschlagenen Technologien:

- Warum Spring Boot und nicht nur Java 21?
- Warum PostgreSQL und nicht eine andere Datenbank?
- Welche Alternativen gibt es zu RabbitMQ und welche Stärken und Schwächen haben sie?
- Soll zunächst Polling und später WebSocket eingesetzt werden?
- Welche Alternativen gibt es zu Flyway?

### Entscheidungen im Verlauf

Nach der Diskussion wurden folgende Entscheidungen konkretisiert:

- **Spring Boot** bleibt das bevorzugte Backend-Framework.
- **PostgreSQL** bleibt die primäre relationale Datenbank.
- **RabbitMQ** bleibt der Message Broker.
- **Flyway** bleibt das Werkzeug für Datenbankmigrationen.
- Für den ersten MVP wird **Polling** verwendet.
- Nach einem stabilen MVP wird **WebSocket** für Realtime-Kommunikation ergänzt.
- Die Web-App bleibt der einzige über `localhost` erreichbare Service.
- Alle anderen Komponenten kommunizieren ausschließlich über das Docker-interne Netzwerk.

### Verworfene oder zurückgestellte Varianten

**Eigenes Login-System:** verworfen. Stattdessen wird Keycloak eingesetzt.

**Quarkus / Micronaut:** technisch geeignet, aber zugunsten von Spring Boot nicht ausgewählt.

**MySQL / MariaDB:** möglich, aber PostgreSQL wird wegen seiner starken relationalen und SQL-Funktionen bevorzugt.

**MongoDB:** nicht bevorzugt, da die Beziehungen zwischen Benutzern, Chats, Mitgliedschaften und Nachrichten relational gut abbildbar sind.

**Redis als Hauptdatenbank:** verworfen. Redis kann später für Cache oder Presence sinnvoll sein.

**Apache Kafka:** für den MVP verworfen, weil die zusätzliche Komplexität für den aktuellen Umfang nicht notwendig ist.

**NATS:** nicht ausgewählt. RabbitMQ passt besser zum vorgesehenen klassischen Queue- und Routing-Modell.

**Redis Streams:** nicht ausgewählt. Interessant bei ohnehin vorhandenem Redis, aber RabbitMQ bleibt der spezialisierte Broker.

**ActiveMQ Artemis:** technisch möglich, aber ohne entscheidenden Vorteil für diesen Anwendungsfall.

**Liquibase:** nicht ausgewählt. Flyway ist für dieses Projekt einfacher und SQL-näher.

**Hibernate `ddl-auto`:** nicht als produktives Migrationssystem vorgesehen.

**Ausschließlich manuelle SQL-Migrationen:** verworfen, da Flyway die Versionsverwaltung und reproduzierbare Ausführung übernimmt.

**WebSocket direkt ab Version 1:** zurückgestellt. Zunächst wird Polling umgesetzt, danach WebSocket.

**Desktop-App im MVP:** zurückgestellt. Die erste Version konzentriert sich auf die Web-App.

**Viele kleine Microservices von Beginn an:** verworfen. Das Chat-Backend startet als zusammenhängender fachlicher Service; weitere Services werden nur bei konkretem Bedarf ausgegliedert.
