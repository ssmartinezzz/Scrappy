package ar.scraper.web;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User-controlled strings reach the log (a rejected username, a search query). A newline in one of
 * them lets an anonymous caller forge whole log lines — an injected {@code [ADMIN]} entry, or a
 * password a user typed into the username field smuggled onto its own line. {@link LogSafe}
 * neutralises line breaks and control characters and caps the length before anything is logged.
 */
@Epic("Security")
@Feature("Log integrity")
@Story("LogSafe neutralises user input before it is logged")
@DisplayName("LogSafe")
class LogSafeTest {

    @Test
    @DisplayName("line breaks and carriage returns become a visible marker, never a real newline")
    void stripsLineBreaks() {
        String forjado = "x\n2026-01-01 00:00:00 INFO forged [ADMIN] login ok";

        String seguro = LogSafe.of(forjado);

        assertThat(seguro).doesNotContain("\n").doesNotContain("\r");
        assertThat(seguro).startsWith("x");
    }

    @Test
    @DisplayName("other control characters are neutralised too")
    void stripsOtherControlChars() {
        assertThat(LogSafe.of("a\tb\u0000c\u001bd")).doesNotContainPattern("[\\x00-\\x1f]");
    }

    @Test
    @DisplayName("a very long value is truncated so one field cannot flood the log")
    void capsLength() {
        String largo = "a".repeat(10_000);

        assertThat(LogSafe.of(largo).length()).isLessThanOrEqualTo(130);
    }

    @Test
    @DisplayName("null and ordinary values pass through legibly")
    void leavesOrdinaryValuesAlone() {
        assertThat(LogSafe.of(null)).isEqualTo("null");
        assertThat(LogSafe.of("zapatillas rojas")).isEqualTo("zapatillas rojas");
    }
}
