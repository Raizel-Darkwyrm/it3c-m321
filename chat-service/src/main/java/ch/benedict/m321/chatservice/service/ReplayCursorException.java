package ch.benedict.m321.chatservice.service;

/** Meldet, dass ein Client-Cursor keine lückenlose Fortsetzung mehr ermöglicht. */
public class ReplayCursorException extends RuntimeException {

    /** Der feste Text verrät keine Nachrichteninhalte und kann als HTTP 409 abgebildet werden. */
    public ReplayCursorException() {
        super("Replay cursor is not available");
    }
}
