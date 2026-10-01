package ar.scraper.agent;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, bilingual (ES+EN) recognition of meta-intent utterances (greetings, "what can you
 * do" / "¿qué podés hacer?", help requests) that short-circuit a turn to a system-authored Spanish
 * response without invoking the provider or any tool.
 */
final class MetaIntents {

    private MetaIntents() {}

    static final Set<String> PHRASES = Set.of(
            "hola", "buenas", "buen dia", "hola que tal", "que podes hacer",
            "que sabes hacer", "que haces", "para que servis", "ayuda", "quien sos",
            "hi", "hello", "hey", "good morning", "what can you do",
            "what do you do", "what are you", "who are you", "help");

    static boolean matches(String rawUserText) {
        if (rawUserText == null) return false;
        String normalized = rawUserText.trim().toLowerCase(Locale.ROOT);
        normalized = AccentStripper.strip(normalized);
        normalized = normalized.replaceAll("[^a-z0-9 ]", "");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return PHRASES.contains(normalized);
    }
}
