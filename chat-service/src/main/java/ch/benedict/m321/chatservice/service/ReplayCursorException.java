package ch.benedict.m321.chatservice.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Meldet, dass ein Client-Cursor keine lückenlose Fortsetzung mehr ermöglicht. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ReplayCursorException extends RuntimeException {

    /** Der feste Text verrät keine Nachrichteninhalte und kann als HTTP 409 abgebildet werden. */
    public ReplayCursorException() {
        super("Replay cursor is not available");
    }
}
