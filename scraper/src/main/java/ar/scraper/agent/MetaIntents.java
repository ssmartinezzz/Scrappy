package ar.scraper.agent;

import ar.scraper.aggregator.text.AccentStripper;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, bilingual (ES+EN) recognition of meta-intent utterances
 * (greetings, "what can you do" / "¿qué podés hacer?", help requests) that
 * short-circuit a turn to a system-authored Spanish response without
 * invoking the provider or any tool (agent-chat-response-quality, design
 * Part 1, Amendment 2). Package-private static, following this project's
 * existing convention of directly unit-testable statics (see
 * {@link OpenAiCompatProvider}'s wire↔domain mapping methods).
 *
 * <p>Matching is WHOLE-UTTERANCE equality against {@link #PHRASES}, never
 * substring containment — this is what keeps "what can you do with nike
 * shirts" out of the set while still recognizing "what can you do" alone.
 * Members are restricted by design to greeting/capability/help phrases only
 * — never a token denoting a garment, brand, colour, size, category, or
 * rubro — and {@code MetaIntentsTest} enforces disjointness against
 * {@code CategoryGroups.canonicalCategories()} as a build-time guard.</p>
 */
final class MetaIntents {

    private MetaIntents() {}

    static final Set<String> PHRASES = Set.of(
            // ES: greetings + capability + help
            "hola", "buenas", "buen dia", "hola que tal", "que podes hacer",
            "que sabes hacer", "que haces", "para que servis", "ayuda", "quien sos",
            // EN: same intents
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
