package ar.scraper.aggregator.normalize;

import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CategoryClassifier#normalizarCategoria(String, String)}.
 *
 * <p>Migrated verbatim from {@code NormalizerServiceTest} (Work Unit 5 of the
 * aggregator SOLID modularization) — same assertions, new collaborator.</p>
 */
@Epic("Normalization")
@Feature("Category")
@DisplayName("CategoryClassifier — category classification from raw/nombre")
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
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Running Sleeves");
        assertThat(classifier.normalizarCategoria(null, "Running Sleeves")).isNotEqualTo("Zapatilla Running");
    }

    @Test
    void bareTrainingKeywordWithoutShoeNounIsNotClassifiedAsShoe() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Training Gloves");
        assertThat(classifier.normalizarCategoria(null, "Training Gloves")).isNotEqualTo("Zapatilla Entrenamiento");
    }

    @Test
    void genericRunningKeywordWithShoeNounStillClassifiesAsShoe() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Zapatillas Running Hombre");
        assertThat(classifier.normalizarCategoria(null, "Zapatillas Running Hombre")).isEqualTo("Zapatilla Running");
    }

    @Test
    void unambiguousModeloNameClassifiesAsShoeWithoutShoeNoun() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Adidas Ultraboost 22");
        assertThat(classifier.normalizarCategoria(null, "Adidas Ultraboost 22")).isEqualTo("Zapatilla Running");
    }

    @Test
    void otherUnambiguousRunningModeloNamesStillClassifyAsShoe() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombrePegasus", "Nike Pegasus 40");
        Allure.parameter("nombreGelKayano", "Asics Gel-Kayano 30");
        Allure.parameter("nombreGhost", "Brooks Ghost 15");
        Allure.parameter("nombreClifton", "Hoka Clifton 9");
        Allure.parameter("nombreNb1080", "New Balance 1080");
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
    @DisplayName("Bare 'slide'/'diapositiva' tokens classify as Ojota")
    void clasificarOjotaSlideYDiapositiva() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombreDiapositivaSlideSandal", "DC Diapositiva Slide Sandal");
        Allure.parameter("nombreSlide", "Slide");
        assertThat(classifier.normalizarCategoria(null, "DC Diapositiva Slide Sandal")).isEqualTo("Ojotas");
        assertThat(classifier.normalizarCategoria(null, "Slide")).isEqualTo("Ojotas");
    }

    // ── KW_BOTIN Tier A: unambiguous tokens still match unconditionally ─────

    @Test
    @DisplayName("Unambiguous Botin Tier-A tokens match without footwear context")
    void clasificarBotinTierAUnambiguousStillMatches() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombrePredator", "Botines Predator");
        Allure.parameter("nombreAce17Fg", "Botines Ace 17 FG");
        assertThat(classifier.normalizarCategoria(null, "Botines Predator")).isEqualTo("Botines");
        assertThat(classifier.normalizarCategoria(null, "Botines Ace 17 FG")).isEqualTo("Botines");
    }

    // ── KW_BOTIN Tier B: ambiguous tokens require footwear context ─────────

    @Test
    @DisplayName("Ambiguous Botin Tier-B tokens do NOT match without footwear context")
    void clasificarBotinGenericoSinContextoNoMatchea() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombreEmbraceTee", "Embrace tee");
        Allure.parameter("nombreGorraSaucony", "Gorra Saucony Pro Future");
        Allure.parameter("nombrePantalonTiempoLibre", "Pantalón Tiempo Libre");
        assertThat(classifier.normalizarCategoria(null, "Embrace tee")).isNotEqualTo("Botines");
        assertThat(classifier.normalizarCategoria(null, "Gorra Saucony Pro Future")).isNotEqualTo("Botines");
        assertThat(classifier.normalizarCategoria(null, "Pantalón Tiempo Libre")).isNotEqualTo("Botines");
    }

    @Test
    @DisplayName("Ambiguous Botin Tier-B tokens match when footwear context co-occurs")
    void clasificarBotinGenericoConContextoMatchea() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Botín de Fútbol Copa");
        assertThat(classifier.normalizarCategoria(null, "Botín de Fútbol Copa")).isEqualTo("Botines");
    }

    // ══════════════════════════════════════════════════════════════════
    // classifier-quality-fixes — category precision bugs
    // ══════════════════════════════════════════════════════════════════

    // ── Timberland: brand name must NOT classify clothing as Borcego ──

    @Test
    void timberlandRemeraNoEsBorcego() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Remera Timberland Hombre");
        assertThat(classifier.normalizarCategoria(null, "Remera Timberland Hombre")).isNotEqualTo("Borcego");
    }

    @Test
    void timberlandCamperaNoEsBorcego() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Campera Timberland Manga Larga");
        assertThat(classifier.normalizarCategoria(null, "Campera Timberland Manga Larga")).isNotEqualTo("Borcego");
    }

    @Test
    void timberlandBootSigueEsBorcego() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Timberland Boot Hiking Hombre");
        assertThat(classifier.normalizarCategoria(null, "Timberland Boot Hiking Hombre")).isEqualTo("Borcego");
    }

    // ── Puffer: broad keywords must not fire on non-jacket items ─────

    @Test
    void infladorNoEsPuffer() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Inflador de pelotas Adidas");
        assertThat(classifier.normalizarCategoria(null, "Inflador de pelotas Adidas")).isNotEqualTo("Puffer");
    }

    @Test
    void botellaTermicaNoEsPuffer() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Botella Termica 500ml Acero");
        assertThat(classifier.normalizarCategoria(null, "Botella Termica 500ml Acero")).isNotEqualTo("Puffer");
    }

    @Test
    void remeraTermicaNoEsPuffer() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Remera Termica Hombre Under Armour");
        assertThat(classifier.normalizarCategoria(null, "Remera Termica Hombre Under Armour")).isNotEqualTo("Puffer");
    }

    @Test
    void remeraTermicaEsRemera() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Remera Termica Hombre Under Armour");
        assertThat(classifier.normalizarCategoria(null, "Remera Termica Hombre Under Armour")).isEqualTo("Remera");
    }

    @Test
    void camperaInflableEsPuffer() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Campera Inflable The North Face");
        assertThat(classifier.normalizarCategoria(null, "Campera Inflable The North Face")).isEqualTo("Puffer");
    }

    @Test
    void camperaAcolchadaEsPuffer() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Campera Acolchada Mujer Nike");
        assertThat(classifier.normalizarCategoria(null, "Campera Acolchada Mujer Nike")).isEqualTo("Puffer");
    }

    // ── Accesorios deportivos: muñequera, shaker ─────────────────────

    @Test
    void munecueraDezapatillasNoEsZapatilla() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Muñequera adidas de Zapatillas Grande Unisex");
        assertThat(classifier.normalizarCategoria(null, "Muñequera adidas de Zapatillas Grande Unisex"))
                .isNotIn("Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento", "Zapatilla Urbana", "Sneaker");
    }

    @Test
    void munecueraEsAccesorioDeportivo() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Muñequera adidas de Zapatillas Grande Unisex");
        assertThat(classifier.normalizarCategoria(null, "Muñequera adidas de Zapatillas Grande Unisex"))
                .isEqualTo("Accesorio Deportivo");
    }

    @Test
    void shakerEsAccesorioDeportivo() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Raw Shaker Elite 700ml Transparente");
        assertThat(classifier.normalizarCategoria(null, "Raw Shaker Elite 700ml Transparente"))
                .isEqualTo("Accesorio Deportivo");
    }

    // ── Alimentos: comidas sin keywords previos caen bien ─────────────

    @Test
    void chiaPuddingEsAlimentos() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "GRANGER Chia Pudding 300g");
        assertThat(classifier.normalizarCategoria(null, "GRANGER Chia Pudding 300g")).isEqualTo("Alimentos");
    }

    @Test
    void salsaMrsTasteEsAlimentos() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "MRS TASTE BBQ Salsa Top Chef");
        assertThat(classifier.normalizarCategoria(null, "MRS TASTE BBQ Salsa Top Chef")).isEqualTo("Alimentos");
    }

    @Test
    void salsaNoEsMusculosa() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "MRS TASTE BBQ Salsa Top Chef");
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
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "SmartDIET Puré de Palmitos 300g");
        assertThat(classifier.normalizarCategoria(null, "SmartDIET Puré de Palmitos 300g")).isEqualTo("Alimentos");
    }

    @Test
    void laGanexaEsAlimentos() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "LA GANEXA Barrita");
        assertThat(classifier.normalizarCategoria(null, "LA GANEXA Barrita")).isEqualTo("Alimentos");
    }

    @Test
    void nutremaxHydromaxEsAlimentos() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "NUTREMAX HYDROMAX");
        assertThat(classifier.normalizarCategoria(null, "NUTREMAX HYDROMAX")).isEqualTo("Alimentos");
    }

    @Test
    void marcaDeAlimentoNoEsIndumentaria() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombreNutremax", "NUTREMAX HYDROMAX");
        Allure.parameter("nombreGanexa", "LA GANEXA");
        assertThat(classifier.normalizarCategoria(null, "NUTREMAX HYDROMAX")).isNotEqualTo("Indumentaria");
        assertThat(classifier.normalizarCategoria(null, "LA GANEXA")).isNotEqualTo("Indumentaria");
    }

    // ── Sustantivo culinario inequívoco robado por keyword de ropa ────

    @Test
    void diablaCookieEsAlimentos() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Diabla Super Cookie");
        assertThat(classifier.normalizarCategoria(null, "Diabla Super Cookie")).isEqualTo("Alimentos");
    }

    @Test
    void pancakeConTopNoEsMusculosa() {
        // " top " matcheaba Musculosa antes de llegar al bloque de nutrición;
        // el portón temprano ahora resuelve la subcategoría específica.
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Pancake Protein Top");
        assertThat(classifier.normalizarCategoria(null, "Pancake Protein Top")).isEqualTo("Pancake Proteico");
    }

    @Test
    void mrTasteMapleSyrupEsAlimentos() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "MR TASTE Maple Syrup Zero");
        assertThat(classifier.normalizarCategoria(null, "MR TASTE Maple Syrup Zero")).isEqualTo("Alimentos");
    }

    // ── El portón NO debe robar indumentaria legítima (no-regresión) ──

    @Test
    void remeraConMaterialSigueSiendoRemera() {
        // "mate" ⊂ "material": si el portón gateara con KW_COMIDA, esto caería
        // en Alimentos. Debe seguir siendo Remera.
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Remera Material Premium Nike");
        assertThat(classifier.normalizarCategoria(null, "Remera Material Premium Nike")).isEqualTo("Remera");
    }

    // ── Suplemento subcategorías ──────────────────────────────────────

    @Test
    void creatinaMononhidratoEsCreatina() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Creatina Monohidrato 300g Myprotein");
        assertThat(classifier.normalizarCategoria(null, "Creatina Monohidrato 300g Myprotein")).isEqualTo("Creatina");
    }

    @Test
    void wheyProteinEsProteina() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Whey Protein Isolate 2kg Vanilla");
        assertThat(classifier.normalizarCategoria(null, "Whey Protein Isolate 2kg Vanilla")).isEqualTo("Proteína");
    }

    @Test
    void magnesioEsMagnesio() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Magnesio Citrato 500mg 60 capsulas");
        assertThat(classifier.normalizarCategoria(null, "Magnesio Citrato 500mg 60 capsulas")).isEqualTo("Magnesio");
    }

    @Test
    void preWorkoutEsPreWorkout() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Pre Workout Explosivo 300g");
        assertThat(classifier.normalizarCategoria(null, "Pre Workout Explosivo 300g")).isEqualTo("Pre-Workout");
    }

    @Test
    void bcaaEsBcaa() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "BCAA 2:1:1 200g Limón");
        assertThat(classifier.normalizarCategoria(null, "BCAA 2:1:1 200g Limón")).isEqualTo("BCAA");
    }

    @Test
    void vitaminaEsVitaminas() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Vitamina C 1000mg 60 capsulas");
        assertThat(classifier.normalizarCategoria(null, "Vitamina C 1000mg 60 capsulas")).isEqualTo("Vitaminas");
    }

    @Test
    void omega3EsVitaminas() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Omega 3 Fish Oil 1000mg 90 softgels");
        assertThat(classifier.normalizarCategoria(null, "Omega 3 Fish Oil 1000mg 90 softgels")).isEqualTo("Vitaminas");
    }

    // ── Fallback peso/volumen: no-textil con indicador de peso ────────

    @Test
    void productoConPesoSinKeywordNoEsIndumentaria() {
        // productos sin keyword conocido pero con indicador de peso → Alimentos, no Indumentaria
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "GRANGER Chia Pudding 300g");
        assertThat(classifier.normalizarCategoria(null, "GRANGER Chia Pudding 300g")).isNotEqualTo("Indumentaria");
    }

    // ══════════════════════════════════════════════════════════════════
    // suplementos-classification-fixes — waffle-knit garments must not be
    // stolen by the nutrition gate (Bug A); GRANGER food brand must reach
    // the nutrition path instead of falling back to Indumentaria (Bug B).
    // ══════════════════════════════════════════════════════════════════

    @Test
    void poloWaffleKnitNoEsAlimentos() {
        // "waffle" is a knit-fabric term on garments (waffle-knit polo/tee); it
        // must NOT fire the nutrition gate and land the garment in a food category.
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Royal Polo Waffle Heavyweight Black");
        assertThat(classifier.normalizarCategoria(null, "Royal Polo Waffle Heavyweight Black"))
                .isNotIn("Alimentos", "Pancake Proteico", "Snack Proteico", "Barra Proteica", "Proteína");
    }

    @Test
    void waffleProteicoSigueSiendoComida() {
        // Real protein waffles disambiguate via "proteico"/"protein" and must
        // still classify as nutrition (regression guard for removing bare waffle).
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "Waffle Proteico Vainilla 500g");
        assertThat(classifier.normalizarCategoria(null, "Waffle Proteico Vainilla 500g"))
                .isEqualTo("Pancake Proteico");
    }

    @Test
    void grangerBrandCupcakeEsAlimentos() {
        // GRANGER is a curated food/supplement brand; any product carrying the
        // brand must reach the nutrition path, not fall back to Indumentaria.
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "GRANGER Cupcake Vainilla");
        assertThat(classifier.normalizarCategoria(null, "GRANGER Cupcake Vainilla")).isEqualTo("Alimentos");
    }

    @Test
    void grangerOmeletteNoEsIndumentaria() {
        Allure.parameter("raw", (String) null);
        Allure.parameter("nombre", "GRANGER Omelette Natural");
        assertThat(classifier.normalizarCategoria(null, "GRANGER Omelette Natural")).isNotEqualTo("Indumentaria");
    }
}
