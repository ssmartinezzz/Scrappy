package ar.scraper.pcs;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The same fixture binds the Python canonicalizer in {@code scripts/benchmarks}: drift is red on either side. */
@DisplayName("TechSpecs.modelo — canonical model key, golden fixture claves.csv")
class ClaveDeModeloTest {

    @ParameterizedTest(name = "{0}: {1} -> [{2}]")
    @CsvFileSource(resources = "/pcs/benchmarks/claves.csv", numLinesToSkip = 1)
    void claveCanonica(String categoria, String texto, String clave) {
        assertThat(TechSpecsParser.parse(texto, categoria).modelo()).isEqualTo(StringUtils.defaultString(clave));
    }
}
