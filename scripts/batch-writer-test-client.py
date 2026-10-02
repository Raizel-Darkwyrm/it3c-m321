"""Temporärer interner Prüfclient; liefert Messdaten, ohne einen Produktdienst einzuführen."""

import concurrent.futures
import json
import sys
import time
import urllib.request
import base64


def request_json(url, body=None, credentials=None):
    """Jede Anfrage hat eine Frist; HTTP-Fehler dürfen nicht als erfolgreiche Annahme gelten."""
    headers = {"Content-Type": "application/json"}
    if credentials is not None:
        raw = credentials.encode("utf-8")
        token = base64.b64encode(raw).decode("ascii")
        headers["Authorization"] = "Basic " + token
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.load(response)
        return response.status, result


def send_message(item):
    """Bewahrt die Zuordnung von Antwort-ID zu ursprünglichem Inhalt und Annahmezeit."""
    status, response = request_json("http://chat-service:8080/messages", item)
    if status != 202:
        raise RuntimeError("Expected HTTP 202")
    return {"id": response["id"], "sentAt": response["sentAt"],
            "content": item["content"], "acceptedAt": time.time()}


def send_group(command):
    """Begrenzte Parallelität erlaubt insbesondere 300 HTTP-Anfragen während des 15-Sekunden-Ausfalls."""
    requests = []
    for number in range(1, command["count"] + 1):
        requests.append({"roomId": command["roomId"], "senderId": "review",
                         "senderName": "Review", "content": command["prefix"] + "-" + str(number)})
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
        for result in executor.map(send_message, requests):
            results.append(result)
    return results


def main():
    """Ein JSON-Auftrag über stdin verhindert Shell-Escaping und Zugangsdaten in Prozessargumenten."""
    command = json.load(sys.stdin)
    if command["action"] == "send":
        result = send_group(command)
    else:
        url = "http://rabbitmq:15672/api/" + command["path"]
        credentials = command["user"] + ":" + command["password"]
        status, result = request_json(url, command.get("body"), credentials)
        if status != 200:
            raise RuntimeError("Expected management HTTP 200")
    json.dump(result, sys.stdout)


if __name__ == "__main__":
    main()
