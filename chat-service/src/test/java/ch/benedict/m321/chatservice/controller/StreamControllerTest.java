package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.service.LiveMessageService;
import ch.benedict.m321.chatservice.service.ReplayCursorException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Prüft den bewerteten HTTP-Vertrag ohne RabbitMQ oder laufenden Server. */
@WebMvcTest(StreamController.class)
class StreamControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LiveMessageService liveMessageService;

    /** Eine gültige Anfrage startet eine asynchrone SSE-Antwort für den angegebenen Raum. */
    @Test
    void opensEventStreamForRoom() throws Exception {
        UUID roomId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(0L);
        when(liveMessageService.subscribe(roomId, null)).thenReturn(emitter);

        MvcResult result = mockMvc.perform(get("/stream").queryParam("roomId", roomId.toString()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        SseEmitter.SseEventBuilder event = SseEmitter.event();
        event.id(UUID.randomUUID().toString());
        event.data("probe");
        emitter.send(event);
        emitter.complete();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));

        verify(liveMessageService).subscribe(roomId, null);
    }

    /** Der standardisierte Header wird als UUID an den Replay-Service weitergegeben. */
    @Test
    void passesLastEventIdToService() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID lastEventId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(0L);
        when(liveMessageService.subscribe(roomId, lastEventId)).thenReturn(emitter);

        mockMvc.perform(get("/stream")
                        .queryParam("roomId", roomId.toString())
                        .header("Last-Event-ID", lastEventId.toString()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(liveMessageService).subscribe(roomId, lastEventId);
        emitter.complete();
    }

    /** Eine syntaktisch ungültige Raum-ID wird vor dem Service als Bad Request abgelehnt. */
    @Test
    void rejectsInvalidRoomId() throws Exception {
        mockMvc.perform(get("/stream").queryParam("roomId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    /** Ein nicht mehr verfügbarer Cursor erhält den spezifizierten Konfliktstatus. */
    @Test
    void reportsUnavailableCursorAsConflict() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID lastEventId = UUID.randomUUID();
        when(liveMessageService.subscribe(roomId, lastEventId)).thenThrow(new ReplayCursorException());

        mockMvc.perform(get("/stream")
                        .queryParam("roomId", roomId.toString())
                        .header("Last-Event-ID", lastEventId.toString()))
                .andExpect(status().isConflict());
    }
}
