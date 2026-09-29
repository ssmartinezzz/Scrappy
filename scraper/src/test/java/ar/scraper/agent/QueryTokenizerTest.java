package ar.scraper.agent;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("search_products — query tokenizer")
@DisplayName("QueryTokenizer")
class QueryTokenizerTest {

    private static List<String> stems(String q) {
        return QueryTokenizer.contentTokens(q).stream().map(QueryTokenizer.Token::stem).toList();
    }

    @Test
    @DisplayName("filler and function words are dropped, the content stays")
    void stopwordsAreDropped() {
        assertThat(stems("Tenés algún SSD SATA de TB")).containsExactly("ssd", "sata", "tb");
        assertThat(stems("hola, quiero una remera para el gym")).containsExactly("remera", "gym");
    }

    @Test
    @DisplayName("colours, sizes, genders, units, brands and 'sin' are real criteria and are never dropped")
    void realCriteriaSurvive() {
        assertThat(stems("remera negra talle xl hombre sin mangas nike 500gb"))
                .containsExactly("remera", "negra", "talle", "xl", "hombre", "sin", "manga", "nike", "500gb");
        assertThat(stems("short m mujer azul")).containsExactly("short", "m", "mujer", "azul");
    }

    @Test
    @DisplayName("a number spaced from its unit becomes one term (1 tb == 1tb)")
    void numberUnitIsGlued() {
        assertThat(stems("ssd 1 tb")).containsExactly("ssd", "1tb");
        assertThat(stems("ssd 500 GB")).containsExactly("ssd", "500gb");
        assertThat(stems("ssd 1tb")).containsExactly("ssd", "1tb");
    }

    @Test
    @DisplayName("splits on any run of non-alphanumerics and strips accents")
    void splitsAndNormalizes() {
        assertThat(stems("Pantalón/Jean—cargo;  ñandú")).containsExactly("pantalon", "jean", "cargo", "nandu");
    }

    @Test
    @DisplayName("light plural stemming: -es after r/l/n/d/z/j, else -s; short words untouched")
    void pluralStemming() {
        assertThat(QueryTokenizer.stem("pantalones")).isEqualTo("pantalon");
        assertThat(QueryTokenizer.stem("mujeres")).isEqualTo("mujer");
        assertThat(QueryTokenizer.stem("zapatillas")).isEqualTo("zapatilla");
        assertThat(QueryTokenizer.stem("remeras")).isEqualTo("remera");
        assertThat(QueryTokenizer.stem("lentes")).isEqualTo("lente");
        assertThat(QueryTokenizer.stem("buzos")).isEqualTo("buzo");
        assertThat(QueryTokenizer.stem("gris")).isEqualTo("gris");   // < 5 chars
        assertThat(QueryTokenizer.stem("mas")).isEqualTo("mas");
    }

    @Test
    @DisplayName("tokens containing digits are never stemmed")
    void digitsAreNeverStemmed() {
        assertThat(QueryTokenizer.stem("rtx3060s")).isEqualTo("rtx3060s");
        assertThat(QueryTokenizer.stem("ddr4es")).isEqualTo("ddr4es");
        assertThat(QueryTokenizer.stem("500gbs")).isEqualTo("500gbs");
    }

    @Test
    @DisplayName("the same tokenization+stemming applies to product text (plural on either side matches)")
    void productTermsUseTheSameStemming() {
        assertThat(QueryTokenizer.terms("Zapatillas Running 1 TB")).contains("zapatilla", "running", "1tb");
        assertThat(stems("zapatilla")).isEqualTo(List.of("zapatilla"));
        // product text keeps function words: field length is part of the BM25 normalization
        assertThat(QueryTokenizer.terms("Remera de Algodón")).containsExactly("remera", "de", "algodon");
    }

    @Test
    @DisplayName("product text keeps the loose words AND the glued number+unit form")
    void productTermsKeepBothForms() {
        assertThat(QueryTokenizer.terms("SSD 1 TB")).containsExactly("ssd", "1", "tb", "1tb");
        // a model code followed by a word must not hide the word
        assertThat(QueryTokenizer.terms("WD SA510 SATA III")).contains("sata", "sa510sata");
    }

    @Test
    @DisplayName("blank and null are empty, not an error")
    void blankIsEmpty() {
        assertThat(QueryTokenizer.contentTokens("  ")).isEmpty();
        assertThat(QueryTokenizer.contentTokens(null)).isEmpty();
        assertThat(QueryTokenizer.terms(null)).isEmpty();
    }

    @Test
    @DisplayName("each token keeps its unstemmed word, to report missing terms as the user wrote them")
    void tokenKeepsTheWord() {
        var t = QueryTokenizer.contentTokens("adidas zapatillas").get(1);
        assertThat(t.word()).isEqualTo("zapatillas");
        assertThat(t.stem()).isEqualTo("zapatilla");
    }
}
