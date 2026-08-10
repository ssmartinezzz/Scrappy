package ar.scraper.aggregator.text;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@link AccentStripper}, which had none despite
 * being the shared normalization step behind ten production classes:
 * {@code BrandExtractor}, {@code GenderResolver}, {@code GymratTagger},
 * {@code SubcategoryResolver}, {@code PackQuantityDetector},
 * {@code ProductIdentity}, {@code JaccardSimilarity}, {@code MetaIntents} and
 * the agent's {@code SearchProductsTool}.
 *
 * <p>That reach is exactly why it was worth making fast — it sits on both the
 * per-request comparator path and the per-scrape normalization path — and
 * exactly why it could not be made fast without pinning its behaviour first.
 * The original was a chain of six {@code String.replaceAll} calls: six freshly
 * compiled {@code Pattern}s and six intermediate strings on every call.</p>
 *
 * <p>The uppercase cases below are not an oversight being enshrined: the class
 * javadoc states callers lower-case their input first, and the original regexes
 * only ever targeted lowercase accented characters. Any rewrite has to keep
 * leaving uppercase alone, so it is pinned.</p>
 */
@Epic("Aggregation & Grouping")
@Feature("Normalización de texto")
@Story("AccentStripper")
@DisplayName("AccentStripper — normalización de acentos")
class AccentStripperTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "á, a", "à, a", "ä, a",
            "é, e", "è, e", "ë, e",
            "í, i", "ì, i", "ï, i",
            "ó, o", "ò, o", "ö, o",
            "ú, u", "ù, u", "ü, u",
            "ñ, n"
    })
    @DisplayName("cada vocal acentuada cae a su vocal base, y la eñe a ene")
    void cadaAcentoCaeASuBase(String entrada, String esperado) {
        assertThat(AccentStripper.strip(entrada)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("normaliza palabras reales del catálogo")
    void normalizaPalabrasRealesDelCatalogo() {
        assertThat(AccentStripper.strip("camión")).isEqualTo("camion");
        assertThat(AccentStripper.strip("algodón peinado")).isEqualTo("algodon peinado");
        assertThat(AccentStripper.strip("niño")).isEqualTo("nino");
        assertThat(AccentStripper.strip("año básico")).isEqualTo("ano basico");
        assertThat(AccentStripper.strip("münchen")).isEqualTo("munchen");
    }

    @Test
    @DisplayName("varios acentos en la misma cadena se reemplazan todos")
    void variosAcentosEnLaMismaCadena() {
        assertThat(AccentStripper.strip("áéíóúñ")).isEqualTo("aeioun");
        assertThat(AccentStripper.strip("àèìòùñ")).isEqualTo("aeioun");
        assertThat(AccentStripper.strip("äëïöüñ")).isEqualTo("aeioun");
        assertThat(AccentStripper.strip("ñññ")).isEqualTo("nnn");
    }

    @Test
    @DisplayName("NO toca mayúsculas acentuadas — los llamadores bajan a minúscula antes")
    void noTocaMayusculasAcentuadas() {
        assertThat(AccentStripper.strip("Ñ")).isEqualTo("Ñ");
        assertThat(AccentStripper.strip("Á")).isEqualTo("Á");
        assertThat(AccentStripper.strip("CAMIÓN")).isEqualTo("CAMIÓN");
    }

    @Test
    @DisplayName("texto sin acentos pasa intacto, símbolos y dígitos incluidos")
    void textoSinAcentosPasaIntacto() {
        assertThat(AccentStripper.strip("nike air force 1")).isEqualTo("nike air force 1");
        assertThat(AccentStripper.strip("remera-oversize_2x/3")).isEqualTo("remera-oversize_2x/3");
        assertThat(AccentStripper.strip("100% algodon (x2)")).isEqualTo("100% algodon (x2)");
    }

    @Test
    @DisplayName("cadena vacía y solo espacios")
    void cadenaVaciaYEspacios() {
        assertThat(AccentStripper.strip("")).isEqualTo("");
        assertThat(AccentStripper.strip("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("es idempotente: aplicarlo dos veces da lo mismo que una")
    void esIdempotente() {
        String texto = "camión de algodón para el niño más pequeño";
        String unaVez = AccentStripper.strip(texto);
        assertThat(AccentStripper.strip(unaVez)).isEqualTo(unaVez);
    }

    @Test
    @DisplayName("deja pasar caracteres fuera del alfabeto latino sin tocarlos")
    void dejaPasarCaracteresNoLatinos() {
        assertThat(AccentStripper.strip("日本 ñ")).isEqualTo("日本 n");
        assertThat(AccentStripper.strip("emoji 🧥 ó")).isEqualTo("emoji 🧥 o");
    }

    @Test
    @DisplayName("no altera acentos que el original nunca contempló (â, ê, ç)")
    void noAlteraAcentosNoContemplados() {
        // El chain original solo cubria agudo/grave/dieresis. Circunflejo y
        // cedilla quedaban intactos, y tienen que seguir quedando.
        assertThat(AccentStripper.strip("â")).isEqualTo("â");
        assertThat(AccentStripper.strip("ê")).isEqualTo("ê");
        assertThat(AccentStripper.strip("ç")).isEqualTo("ç");
        assertThat(AccentStripper.strip("ã")).isEqualTo("ã");
    }
}
