package ar.scraper.agent;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Terminal classification of a single chat turn (agent-chat-response-quality,
 * design Part 1). Distinguishes a grounded, complete answer from the three
 * other terminal states so the frontend never treats an ungrounded or
 * exhausted turn as equivalent to a real answer.
 */
public enum TurnOutcome {
    COMPLETE("complete"),
    CAPABILITY("capability"),
    UNGROUNDED("ungrounded"),
    EXHAUSTED("exhausted");

    private final String wire;

    TurnOutcome(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
