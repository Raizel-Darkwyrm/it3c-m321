package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.service.LiveMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** Stellt den festen SSE-Vertrag bereit und überlässt Zustand und Zustellung dem Service. */
@RestController
@RequiredArgsConstructor
public class StreamController {

    private final LiveMessageService liveMessageService;

    /** Öffnet einen Raumstream und verwendet den optionalen Cursor für ein lückenloses Replay. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam UUID roomId,
                             @RequestHeader(value = "Last-Event-ID", required = false) UUID lastEventId) {
        return liveMessageService.subscribe(roomId, lastEventId);
    }
}
