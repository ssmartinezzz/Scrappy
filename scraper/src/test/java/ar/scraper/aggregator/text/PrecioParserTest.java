package ar.scraper.aggregator.text;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link PrecioParser}'s AR-locale contract against
 * {@code price-parser-cases.tsv} — the SAME fixture {@code V17BackfillParityTest}
 * feeds to {@code sp_parse_precio_ar}. Neither test can go green while the two
 * implementations' semantics differ (design DD2's anti-drift mechanism).
 *
 * <p>The contract is ported verbatim from {@code ml_pipeline.py:354-389}'s
 * {@code safe_price} — that function is the spec, not a competitor.</p>
 */
@Epic("Aggregation & Grouping")
@Feature("Normalización de texto")
@Story("PrecioParser")
@DisplayName("PrecioParser — parseo de precio AR-locale")
class PrecioParserTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @MethodSource("fixtureCases")
    @DisplayName("cada caso del fixture compartido con V17BackfillParityTest parsea igual")
    void parseaCasosDelFixture(String raw, String esperado) {
        OptionalDouble resultado = PrecioParser.parse(raw);
        if ("NULL".equals(esperado)) {
            assertThat(resultado).isEmpty();
        } else {
            assertThat(resultado).isPresent();
            assertThat(resultado.getAsDouble()).isEqualTo(Double.parseDouble(esperado));
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> fixtureCases() throws IOException {
        List<org.junit.jupiter.params.provider.Arguments> args = new ArrayList<>();
        try (InputStream in = PrecioParserTest.class.getClassLoader()
                .getResourceAsStream("price-parser-cases.tsv")) {
            if (in == null) throw new IOException("price-parser-cases.tsv no encontrado en el classpath");
            try (var reader = new java.io.BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    if (tab < 0) continue;
                    String raw = line.substring(0, tab);
                    String expected = line.substring(tab + 1).trim();
                    args.add(org.junit.jupiter.params.provider.Arguments.of(raw, expected));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return args.stream();
    }

    @Test
    @DisplayName("null es \"sin opinion\" (CODE-5), no dispara NPE")
    void nullEsAbstencion() {
        assertThat(PrecioParser.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("blank es abstencion")
    void blankEsAbstencion() {
        assertThat(PrecioParser.parse("")).isEmpty();
        assertThat(PrecioParser.parse("   ")).isEmpty();
    }
}
