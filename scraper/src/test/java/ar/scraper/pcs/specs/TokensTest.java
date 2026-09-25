package ar.scraper.pcs.specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tokens — tokenizes once, space is the word boundary")
class TokensTest {

    @Test
    void splitsOnEveryNonAlphanumericAndLowercases() {
        Tokens t = Tokens.de("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5");

        assertThat(t.array()).contains("b850m", "e", "am5", "ddr5");
        assertThat(t.has("am5")).isTrue();
        assertThat(t.has("b850m")).isTrue();
    }

    @Test
    void paddedWrapsWithLeadingAndTrailingSpace() {
        Tokens t = Tokens.de("Ryzen 9 7900");

        assertThat(t.padded()).isEqualTo(" ryzen 9 7900 ");
    }

    @Test
    void hasNeverMatchesASubstringInsideALongerToken() {
        // "1851" must not match inside a SKU like "sku21851034" — tokens
        // compare whole, never as a substring (same guarantee TechSpecsParser
        // already relied on for the chipset/socket rules).
        Tokens t = Tokens.de("ASRock B650 Steel Legend SKU21851034");

        assertThat(t.has("1851")).isFalse();
    }

    @Test
    void emptyNameYieldsEmptyTokens() {
        Tokens t = Tokens.de("   ");

        assertThat(t.array()).isEmpty();
        assertThat(t.has("anything")).isFalse();
    }

    @Test
    void originalPreservesPunctuationThatTokenizationThrowsAway() {
        // "1.92TB" tokeniza a "1"+"92tb" — el punto decimal se pierde. Un
        // llamador que lo necesite de vuelta (AlmacenamientoSpecsReader, para
        // capacidades decimales) lee `original()`, que es el string
        // acento-stripeado y lowercased ANTES del colapso a espacios.
        Tokens t = Tokens.de("HD SSD 1.92TB KINGSTON");

        assertThat(t.original()).isEqualTo("hd ssd 1.92tb kingston");
        assertThat(t.array()).containsExactly("hd", "ssd", "1", "92tb", "kingston");
    }
}
