package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CategoryClassifier#normalizarCategoria(String, String)}.
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 5 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
class CategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    // ══════════════════════════════════════════════════════════════════
    // KW_*_MODELO/GENERICO split (mejores-picks-fixes Issue 3): a bare
    // generic term (e.g. "running", "training") must NOT, by itself,
    // classify a product as a shoe. It must co-occur with a shoe-noun
    // (esZapatilla) OR the name must match an unambiguous MODELO entry.
    // ══════════════════════════════════════════════════════════════════

    @Test
    void bareRunningKeywordWithoutShoeNounIsNotClassifiedAsShoe() {
        assertThat(classifier.normalizarCategoria(null, "Running Sleeves")).isNotEqualTo("Zapatilla Running");
    }

    @Test
    void bareTrainingKeywordWithoutShoeNounIsNotClassifiedAsShoe() {
        assertThat(classifier.normalizarCategoria(null, "Training Gloves")).isNotEqualTo("Zapatilla Entrenamiento");
    }

    @Test
    void genericRunningKeywordWithShoeNounStillClassifiesAsShoe() {
        assertThat(classifier.normalizarCategoria(null, "Zapatillas Running Hombre")).isEqualTo("Zapatilla Running");
    }

    @Test
    void unambiguousModeloNameClassifiesAsShoeWithoutShoeNoun() {
        assertThat(classifier.normalizarCategoria(null, "Adidas Ultraboost 22")).isEqualTo("Zapatilla Running");
    }

    @Test
    void otherUnambiguousRunningModeloNamesStillClassifyAsShoe() {
        assertThat(classifier.normalizarCategoria(null, "Nike Pegasus 40")).as("Pegasus").isEqualTo("Zapatilla Running");
        assertThat(classifier.normalizarCategoria(null, "Asics Gel-Kayano 30")).as("Gel-Kayano").isEqualTo("Zapatilla Running");
        assertThat(classifier.normalizarCategoria(null, "Brooks Ghost 15")).as("Ghost").isEqualTo("Zapatilla Running");
        assertThat(classifier.normalizarCategoria(null, "Hoka Clifton 9")).as("Clifton").isEqualTo("Zapatilla Running");
        assertThat(classifier.normalizarCategoria(null, "New Balance 1080")).as("NB1080").isEqualTo("Zapatilla Running");
    }

    // ══════════════════════════════════════════════════════════════════
    // category-brand-quality-fixes — Phase 1 (RED): NormalizerService
    // ══════════════════════════════════════════════════════════════════

    // ── KW_OJOTA: bare "diapositiva"/"slide" tokens (Bug: slide sandals) ────

    @Test
    void clasificarOjotaSlideYDiapositiva() {
        assertThat(classifier.normalizarCategoria(null, "DC Diapositiva Slide Sandal")).isEqualTo("Ojotas");
        assertThat(classifier.normalizarCategoria(null, "Slide")).isEqualTo("Ojotas");
    }

    // ── KW_BOTIN Tier A: unambiguous tokens still match unconditionally ─────

    @Test
    void clasificarBotinTierAUnambiguousStillMatches() {
        assertThat(classifier.normalizarCategoria(null, "Botines Predator")).isEqualTo("Botines");
        assertThat(classifier.normalizarCategoria(null, "Botines Ace 17 FG")).isEqualTo("Botines");
    }

    // ── KW_BOTIN Tier B: ambiguous tokens require footwear context ─────────

    @Test
    void clasificarBotinGenericoSinContextoNoMatchea() {
        assertThat(classifier.normalizarCategoria(null, "Embrace tee")).isNotEqualTo("Botines");
        assertThat(classifier.normalizarCategoria(null, "Gorra Saucony Pro Future")).isNotEqualTo("Botines");
        assertThat(classifier.normalizarCategoria(null, "Pantalón Tiempo Libre")).isNotEqualTo("Botines");
    }

    @Test
    void clasificarBotinGenericoConContextoMatchea() {
        assertThat(classifier.normalizarCategoria(null, "Botín de Fútbol Copa")).isEqualTo("Botines");
    }

    // ══════════════════════════════════════════════════════════════════
    // classifier-quality-fixes — category precision bugs
    // ══════════════════════════════════════════════════════════════════

    // ── Timberland: brand name must NOT classify clothing as Borcego ──

    @Test
    void timberlandRemeraNoEsBorcego() {
        assertThat(classifier.normalizarCategoria(null, "Remera Timberland Hombre")).isNotEqualTo("Borcego");
    }

    @Test
    void timberlandCamperaNoEsBorcego() {
        assertThat(classifier.normalizarCategoria(null, "Campera Timberland Manga Larga")).isNotEqualTo("Borcego");
    }

    @Test
    void timberlandBootSigueEsBorcego() {
        assertThat(classifier.normalizarCategoria(null, "Timberland Boot Hiking Hombre")).isEqualTo("Borcego");
    }

    // ── Puffer: broad keywords must not fire on non-jacket items ─────

    @Test
    void infladorNoEsPuffer() {
        assertThat(classifier.normalizarCategoria(null, "Inflador de pelotas Adidas")).isNotEqualTo("Puffer");
    }

    @Test
    void botellaTermicaNoEsPuffer() {
        assertThat(classifier.normalizarCategoria(null, "Botella Termica 500ml Acero")).isNotEqualTo("Puffer");
    }

    @Test
    void remeraTermicaNoEsPuffer() {
        assertThat(classifier.normalizarCategoria(null, "Remera Termica Hombre Under Armour")).isNotEqualTo("Puffer");
    }

    @Test
    void remeraTermicaEsRemera() {
        assertThat(classifier.normalizarCategoria(null, "Remera Termica Hombre Under Armour")).isEqualTo("Remera");
    }

    @Test
    void camperaInflableEsPuffer() {
        assertThat(classifier.normalizarCategoria(null, "Campera Inflable The North Face")).isEqualTo("Puffer");
    }

    @Test
    void camperaAcolchadaEsPuffer() {
        assertThat(classifier.normalizarCategoria(null, "Campera Acolchada Mujer Nike")).isEqualTo("Puffer");
    }

    // ── Accesorios deportivos: muñequera, shaker ─────────────────────

    @Test
    void munecueraDezapatillasNoEsZapatilla() {
        assertThat(classifier.normalizarCategoria(null, "Muñequera adidas de Zapatillas Grande Unisex"))
                .isNotIn("Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento", "Zapatilla Urbana", "Sneaker");
    }

    @Test
    void munecueraEsAccesorioDeportivo() {
        assertThat(classifier.normalizarCategoria(null, "Muñequera adidas de Zapatillas Grande Unisex"))
                .isEqualTo("Accesorio Deportivo");
    }

    @Test
    void shakerEsAccesorioDeportivo() {
        assertThat(classifier.normalizarCategoria(null, "Raw Shaker Elite 700ml Transparente"))
                .isEqualTo("Accesorio Deportivo");
    }

    // ── Alimentos: comidas sin keywords previos caen bien ─────────────

    @Test
    void chiaPuddingEsAlimentos() {
        assertThat(classifier.normalizarCategoria(null, "GRANGER Chia Pudding 300g")).isEqualTo("Alimentos");
    }

    @Test
    void salsaMrsTasteEsAlimentos() {
        assertThat(classifier.normalizarCategoria(null, "MRS TASTE BBQ Salsa Top Chef")).isEqualTo("Alimentos");
    }

    @Test
    void salsaNoEsMusculosa() {
        assertThat(classifier.normalizarCategoria(null, "MRS TASTE BBQ Salsa Top Chef")).isNotEqualTo("Musculosa");
    }

    // ══════════════════════════════════════════════════════════════════
    // food-brand-tagging — marcas de alimento/suplemento y sustantivos
    // culinarios inequívocos NO deben caer en el fallback "Indumentaria".
    // Casos reales reportados por el product owner (gym sites que mezclan
    // ropa + comida: Monkyforce, Fursten, Bulks, Fuark).
    // ══════════════════════════════════════════════════════════════════

    // ── Marca de alimento sin sustantivo de comida conocido ──────────

    @Test
    void smartDietPureDePalmitosEsAlimentos() {
        assertThat(classifier.normalizarCategoria(null, "SmartDIET Puré de Palmitos 300g")).isEqualTo("Alimentos");
    }

    @Test
    void laGanexaEsAlimentos() {
        assertThat(classifier.normalizarCategoria(null, "LA GANEXA Barrita")).isEqualTo("Alimentos");
    }

    @Test
    void nutremaxHydromaxEsAlimentos() {
        assertThat(classifier.normalizarCategoria(null, "NUTREMAX HYDROMAX")).isEqualTo("Alimentos");
    }

    @Test
    void marcaDeAlimentoNoEsIndumentaria() {
        assertThat(classifier.normalizarCategoria(null, "NUTREMAX HYDROMAX")).isNotEqualTo("Indumentaria");
        assertThat(classifier.normalizarCategoria(null, "LA GANEXA")).isNotEqualTo("Indumentaria");
    }

    // ── Sustantivo culinario inequívoco robado por keyword de ropa ────

    @Test
    void diablaCookieEsAlimentos() {
        assertThat(classifier.normalizarCategoria(null, "Diabla Super Cookie")).isEqualTo("Alimentos");
    }

    @Test
    void pancakeConTopNoEsMusculosa() {
        // " top " matcheaba Musculosa antes de llegar al bloque de nutrición;
        // el portón temprano ahora resuelve la subcategoría específica.
        assertThat(classifier.normalizarCategoria(null, "Pancake Protein Top")).isEqualTo("Pancake Proteico");
    }

    @Test
    void mrTasteMapleSyrupEsAlimentos() {
        assertThat(classifier.normalizarCategoria(null, "MR TASTE Maple Syrup Zero")).isEqualTo("Alimentos");
    }

    // ── El portón NO debe robar indumentaria legítima (no-regresión) ──

    @Test
    void remeraConMaterialSigueSiendoRemera() {
        // "mate" ⊂ "material": si el portón gateara con KW_COMIDA, esto caería
        // en Alimentos. Debe seguir siendo Remera.
        assertThat(classifier.normalizarCategoria(null, "Remera Material Premium Nike")).isEqualTo("Remera");
    }

    // ── Suplemento subcategorías ──────────────────────────────────────

    @Test
    void creatinaMononhidratoEsCreatina() {
        assertThat(classifier.normalizarCategoria(null, "Creatina Monohidrato 300g Myprotein")).isEqualTo("Creatina");
    }

    @Test
    void wheyProteinEsProteina() {
        assertThat(classifier.normalizarCategoria(null, "Whey Protein Isolate 2kg Vanilla")).isEqualTo("Proteína");
    }

    @Test
    void magnesioEsMagnesio() {
        assertThat(classifier.normalizarCategoria(null, "Magnesio Citrato 500mg 60 capsulas")).isEqualTo("Magnesio");
    }

    @Test
    void preWorkoutEsPreWorkout() {
        assertThat(classifier.normalizarCategoria(null, "Pre Workout Explosivo 300g")).isEqualTo("Pre-Workout");
    }

    @Test
    void bcaaEsBcaa() {
        assertThat(classifier.normalizarCategoria(null, "BCAA 2:1:1 200g Limón")).isEqualTo("BCAA");
    }

    @Test
    void vitaminaEsVitaminas() {
        assertThat(classifier.normalizarCategoria(null, "Vitamina C 1000mg 60 capsulas")).isEqualTo("Vitaminas");
    }

    @Test
    void omega3EsVitaminas() {
        assertThat(classifier.normalizarCategoria(null, "Omega 3 Fish Oil 1000mg 90 softgels")).isEqualTo("Vitaminas");
    }

    // ── Fallback peso/volumen: no-textil con indicador de peso ────────

    @Test
    void productoConPesoSinKeywordNoEsIndumentaria() {
        // productos sin keyword conocido pero con indicador de peso → Alimentos, no Indumentaria
        assertThat(classifier.normalizarCategoria(null, "GRANGER Chia Pudding 300g")).isNotEqualTo("Indumentaria");
    }
}
