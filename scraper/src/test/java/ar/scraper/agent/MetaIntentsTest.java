package ar.scraper.agent;

import ar.scraper.aggregator.normalize.CategoryGroups;
import ar.scraper.aggregator.text.AccentStripper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED→GREEN coverage for {@link MetaIntents} (agent-chat-response-quality,
 * task 2.1/3.1 — design Part 1, Amendment 2): bilingual (ES+EN),
 * whole-utterance meta-intent recognition, with a build-enforced guard that
 * the phrase set can never collide with a real catalog category.
 */
@Epic("LLM Catalog Agent")
@Feature("MetaIntents")
@Story("Bilingual whole-utterance meta-intent recognition")
@DisplayName("MetaIntents")
class MetaIntentsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "hola", "Hola", "buenas", "buen dia", "hola que tal",
            "que podes hacer", "qué podés hacer", "que sabes hacer",
            "que haces", "para que servis", "ayuda", "quien sos",
            "hi", "hello", "hey", "good morning", "what can you do",
            "What can you do?", "what do you do", "what are you", "who are you", "help"
    })
    @DisplayName("ES/EN greeting, capability, and help phrases match")
    void esAndEnPhrasesMatch(String phrase) {
        assertThat(MetaIntents.matches(phrase)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "what can you do with nike shirts",
            "¿qué remeras hay de Nike?",
            "hoodie",
            "tenés algo cargo?",
            "show me the oversize hoodies"
    })
    @DisplayName("Product/brand terms and longer catalog questions are NOT classified as meta-intent")
    void catalogQuestionsDoNotMatch(String utterance) {
        assertThat(MetaIntents.matches(utterance)).isFalse();
    }

    @Test
    @DisplayName("Punctuation, accents, and case are normalized before matching")
    void normalizesPunctuationAccentsAndCase() {
        assertThat(MetaIntents.matches("¿Qué podés hacer?")).isTrue();
        assertThat(MetaIntents.matches("  HOLA!!  ")).isTrue();
        assertThat(MetaIntents.matches("Hola, ¿cómo estás?")).isFalse(); // not an exact PHRASES member
    }

    @Test
    @DisplayName("PHRASES is disjoint from CategoryGroups.canonicalCategories() (build-enforced invariant)")
    void phrasesDisjointFromCanonicalCategories() {
        Set<String> normalizedCategories = CategoryGroups.canonicalCategories().stream()
                .map(c -> c.trim().toLowerCase(Locale.ROOT))
                .map(AccentStripper::strip)
                .map(c -> c.replaceAll("[^a-z0-9 ]", ""))
                .collect(Collectors.toSet());

        assertThat(MetaIntents.PHRASES).doesNotContainAnyElementsOf(normalizedCategories);
    }
}
