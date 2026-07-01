package ar.scraper.aggregator;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NormalizerService}'s classification, brand, gender,
 * subcategory, and orchestration behavior.
 *
 * <p>Pack/combo unit-count detection tests migrated to
 * {@code ar.scraper.aggregator.normalize.PackQuantityDetectorTest} (Work Unit 4
 * of the aggregator SOLID modularization).</p>
 */
class NormalizerServiceTest {

    private final NormalizerService service = new NormalizerService();

    // ══════════════════════════════════════════════════════════════════
    // KW_*_MODELO/GENERICO split (mejores-picks-fixes Issue 3): a bare
    // generic term (e.g. "running", "training") must NOT, by itself,
    // classify a product as a shoe. It must co-occur with a shoe-noun
    // (esZapatilla) OR the name must match an unambiguous MODELO entry.
    // ══════════════════════════════════════════════════════════════════

    @Test
    void bareRunningKeywordWithoutShoeNounIsNotClassifiedAsShoe() {
        assertThat(service.normalizarCategoria(null, "Running Sleeves")).isNotEqualTo("Zapatilla Running");
    }

    @Test
    void bareTrainingKeywordWithoutShoeNounIsNotClassifiedAsShoe() {
        assertThat(service.normalizarCategoria(null, "Training Gloves")).isNotEqualTo("Zapatilla Entrenamiento");
    }

    @Test
    void genericRunningKeywordWithShoeNounStillClassifiesAsShoe() {
        assertThat(service.normalizarCategoria(null, "Zapatillas Running Hombre")).isEqualTo("Zapatilla Running");
    }

    @Test
    void unambiguousModeloNameClassifiesAsShoeWithoutShoeNoun() {
        assertThat(service.normalizarCategoria(null, "Adidas Ultraboost 22")).isEqualTo("Zapatilla Running");
    }

    @Test
    void otherUnambiguousRunningModeloNamesStillClassifyAsShoe() {
        assertThat(service.normalizarCategoria(null, "Nike Pegasus 40")).as("Pegasus").isEqualTo("Zapatilla Running");
        assertThat(service.normalizarCategoria(null, "Asics Gel-Kayano 30")).as("Gel-Kayano").isEqualTo("Zapatilla Running");
        assertThat(service.normalizarCategoria(null, "Brooks Ghost 15")).as("Ghost").isEqualTo("Zapatilla Running");
        assertThat(service.normalizarCategoria(null, "Hoka Clifton 9")).as("Clifton").isEqualTo("Zapatilla Running");
        assertThat(service.normalizarCategoria(null, "New Balance 1080")).as("NB1080").isEqualTo("Zapatilla Running");
    }

    // ══════════════════════════════════════════════════════════════════
    // category-brand-quality-fixes — Phase 1 (RED): NormalizerService
    // ══════════════════════════════════════════════════════════════════

    // ── KW_OJOTA: bare "diapositiva"/"slide" tokens (Bug: slide sandals) ────

    @Test
    void clasificarOjotaSlideYDiapositiva() {
        assertThat(service.normalizarCategoria(null, "DC Diapositiva Slide Sandal")).isEqualTo("Ojotas");
        assertThat(service.normalizarCategoria(null, "Slide")).isEqualTo("Ojotas");
    }

    // ── KW_BOTIN Tier A: unambiguous tokens still match unconditionally ─────

    @Test
    void clasificarBotinTierAUnambiguousStillMatches() {
        assertThat(service.normalizarCategoria(null, "Botines Predator")).isEqualTo("Botines");
        assertThat(service.normalizarCategoria(null, "Botines Ace 17 FG")).isEqualTo("Botines");
    }

    // ── KW_BOTIN Tier B: ambiguous tokens require footwear context ─────────

    @Test
    void clasificarBotinGenericoSinContextoNoMatchea() {
        assertThat(service.normalizarCategoria(null, "Embrace tee")).isNotEqualTo("Botines");
        assertThat(service.normalizarCategoria(null, "Gorra Saucony Pro Future")).isNotEqualTo("Botines");
        assertThat(service.normalizarCategoria(null, "Pantalón Tiempo Libre")).isNotEqualTo("Botines");
    }

    @Test
    void clasificarBotinGenericoConContextoMatchea() {
        assertThat(service.normalizarCategoria(null, "Botín de Fútbol Copa")).isEqualTo("Botines");
    }

    // ── extraerMarca: no capitalized-word fallback, falls back to sitio ────

    @Test
    void extraerMarcaSinMatchCuradoUsaSitio() {
        assertThat(service.extraerMarca("Remera Oversize Crop", "VCP")).isEqualTo("VCP");
    }

    @Test
    void extraerMarcaSinMatchYSinSitioRetornaVacio() {
        assertThat(service.extraerMarca("Remera Oversize Crop", null)).isEqualTo("");
    }

    @Test
    void extraerMarcaCuradaTieneSiemprePrioridad() {
        assertThat(service.extraerMarca("Nike Air Max", "VCP")).isEqualTo("Nike");
    }

    // ── extraerMarca: word-boundary matching, no substring false positives ──
    // Bug real visto en producción: "DC" (2 letras) matcheaba como substring
    // dentro de "Hardcore" y "HDCP", asignando marca "DC" a jeans/camperas/
    // cables que no tienen nada que ver con la marca de skate.

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHardcore() {
        assertThat(service.extraerMarca("Jean [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
        assertThat(service.extraerMarca("Campera [ Hardcore Desire ] Stone", "Bullbenny")).isEqualTo("Bullbenny");
    }

    @Test
    void extraerMarcaNoMatcheaDcDentroDeHdcp() {
        assertThat(service.extraerMarca("Cable Display Port 8k 60hz Hdr G-sync Hdcp 3 M Vention", "Compragamer"))
                .isEqualTo("Compragamer");
    }

    @Test
    void extraerMarcaSigueMatcheandoDcComoTokenReal() {
        assertThat(service.extraerMarca("Zapatillas Dc Court Graffik Ss", "City")).isEqualTo("DC");
        assertThat(service.extraerMarca("Botas de Invierno Dc Shoes Crisis 2 Hi", "Dcshoes")).isEqualTo("DC");
    }

    // ══════════════════════════════════════════════════════════════════
    // outfits-v2 — Gender inference: feminine-coded categories
    //
    // Covers the FEMININE_CODED_CATEGORIES override that must fire BEFORE
    // the combined-check (raw+nombre), preventing VTEX catalog-level raw
    // tags like "Hombre" from leaking into women's apparel.
    // ══════════════════════════════════════════════════════════════════

    @Test
    void calzaConRawVtexHombreDevuelveMujer() {
        // VTEX sets raw="Hombre" at category level even for women's calzas.
        // The feminine-coded override must fire before combined.contains("hombre").
        assertThat(service.normalizarGenero("Hombre", "Calza Nike Training", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void calzaSinSenalDevuelveMujer() {
        // No raw, no nombre signal — FEMININE_CODED_CATEGORIES fallback produces "mujer".
        assertThat(service.normalizarGenero("", "Calza Sport Corta", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void calzaConDeHombreEnNombreDevuelveHombre() {
        // Explicit masculine signal "de Hombre" in nombre overrides category inference.
        assertThat(service.normalizarGenero("", "Calza adidas Techfit De Hombre", "Calza"))
                .isEqualTo("hombre");
    }

    @Test
    void calzaConHombreComoTokenEnNombreDevuelveHombre() {
        // Standalone "hombre" in nombre (e.g. "Calza Hombre Training") is a valid
        // masculine signal — must NOT be overridden to mujer.
        assertThat(service.normalizarGenero("", "Calza Hombre Training", "Calza"))
                .isEqualTo("hombre");
    }

    @Test
    void leggingConRawHombreDevuelveMujer() {
        // "Legging" normalizes to category Calza — same FEMININE_CODED override applies.
        assertThat(service.normalizarGenero("Hombre", "Legging Gym Pro", "Calza"))
                .isEqualTo("mujer");
    }

    @Test
    void polleraConRawHombreDevuelveMujer() {
        // Pollera is feminine-coded; raw="Hombre" from any site must be overridden.
        assertThat(service.normalizarGenero("Hombre", "Pollera Mini Tiro Alto", "Pollera"))
                .isEqualTo("mujer");
    }

    @Test
    void vestidoConRawHombreDevuelveMujer() {
        assertThat(service.normalizarGenero("Hombre", "Vestido Playero", "Vestido"))
                .isEqualTo("mujer");
    }

    @Test
    void categoriaNoFemeninaConRawHombreDevuelveHombre() {
        // Categories outside FEMININE_CODED_CATEGORIES must NOT be overridden.
        assertThat(service.normalizarGenero("Hombre", "Remera Deportiva", "Remera"))
                .isEqualTo("hombre");
    }

    @Test
    void categoriaNoFemeninaConRawMujerDevuelveMujer() {
        // Verify non-feminine categories still respect raw="mujer" normally.
        assertThat(service.normalizarGenero("mujer", "Remera Básica", "Remera"))
                .isEqualTo("mujer");
    }

    // ══════════════════════════════════════════════════════════════════
    // classifier-quality-fixes — category precision bugs
    // ══════════════════════════════════════════════════════════════════

    // ── Timberland: brand name must NOT classify clothing as Borcego ──

    @Test
    void timberlandRemeraNoEsBorcego() {
        assertThat(service.normalizarCategoria(null, "Remera Timberland Hombre")).isNotEqualTo("Borcego");
    }

    @Test
    void timberlandCamperaNoEsBorcego() {
        assertThat(service.normalizarCategoria(null, "Campera Timberland Manga Larga")).isNotEqualTo("Borcego");
    }

    @Test
    void timberlandBootSigueEsBorcego() {
        assertThat(service.normalizarCategoria(null, "Timberland Boot Hiking Hombre")).isEqualTo("Borcego");
    }

    // ── Puffer: broad keywords must not fire on non-jacket items ─────

    @Test
    void infladorNoEsPuffer() {
        assertThat(service.normalizarCategoria(null, "Inflador de pelotas Adidas")).isNotEqualTo("Puffer");
    }

    @Test
    void botellaTermicaNoEsPuffer() {
        assertThat(service.normalizarCategoria(null, "Botella Termica 500ml Acero")).isNotEqualTo("Puffer");
    }

    @Test
    void remeraTermicaNoEsPuffer() {
        assertThat(service.normalizarCategoria(null, "Remera Termica Hombre Under Armour")).isNotEqualTo("Puffer");
    }

    @Test
    void remeraTermicaEsRemera() {
        assertThat(service.normalizarCategoria(null, "Remera Termica Hombre Under Armour")).isEqualTo("Remera");
    }

    @Test
    void camperaInflableEsPuffer() {
        assertThat(service.normalizarCategoria(null, "Campera Inflable The North Face")).isEqualTo("Puffer");
    }

    @Test
    void camperaAcolchadaEsPuffer() {
        assertThat(service.normalizarCategoria(null, "Campera Acolchada Mujer Nike")).isEqualTo("Puffer");
    }

    // ── Accesorios deportivos: muñequera, shaker ─────────────────────

    @Test
    void munecueraDezapatillasNoEsZapatilla() {
        assertThat(service.normalizarCategoria(null, "Muñequera adidas de Zapatillas Grande Unisex"))
                .isNotIn("Zapatilla", "Zapatilla Running", "Zapatilla Entrenamiento", "Zapatilla Urbana", "Sneaker");
    }

    @Test
    void munecueraEsAccesorioDeportivo() {
        assertThat(service.normalizarCategoria(null, "Muñequera adidas de Zapatillas Grande Unisex"))
                .isEqualTo("Accesorio Deportivo");
    }

    @Test
    void shakerEsAccesorioDeportivo() {
        assertThat(service.normalizarCategoria(null, "Raw Shaker Elite 700ml Transparente"))
                .isEqualTo("Accesorio Deportivo");
    }

    // ── Alimentos: comidas sin keywords previos caen bien ─────────────

    @Test
    void chiaPuddingEsAlimentos() {
        assertThat(service.normalizarCategoria(null, "GRANGER Chia Pudding 300g")).isEqualTo("Alimentos");
    }

    @Test
    void salsaMrsTasteEsAlimentos() {
        assertThat(service.normalizarCategoria(null, "MRS TASTE BBQ Salsa Top Chef")).isEqualTo("Alimentos");
    }

    @Test
    void salsaNoEsMusculosa() {
        assertThat(service.normalizarCategoria(null, "MRS TASTE BBQ Salsa Top Chef")).isNotEqualTo("Musculosa");
    }

    // ── Suplemento subcategorías ──────────────────────────────────────

    @Test
    void creatinaMononhidratoEsCreatina() {
        assertThat(service.normalizarCategoria(null, "Creatina Monohidrato 300g Myprotein")).isEqualTo("Creatina");
    }

    @Test
    void wheyProteinEsProteina() {
        assertThat(service.normalizarCategoria(null, "Whey Protein Isolate 2kg Vanilla")).isEqualTo("Proteína");
    }

    @Test
    void magnesioEsMagnesio() {
        assertThat(service.normalizarCategoria(null, "Magnesio Citrato 500mg 60 capsulas")).isEqualTo("Magnesio");
    }

    @Test
    void preWorkoutEsPreWorkout() {
        assertThat(service.normalizarCategoria(null, "Pre Workout Explosivo 300g")).isEqualTo("Pre-Workout");
    }

    @Test
    void bcaaEsBcaa() {
        assertThat(service.normalizarCategoria(null, "BCAA 2:1:1 200g Limón")).isEqualTo("BCAA");
    }

    @Test
    void vitaminaEsVitaminas() {
        assertThat(service.normalizarCategoria(null, "Vitamina C 1000mg 60 capsulas")).isEqualTo("Vitaminas");
    }

    @Test
    void omega3EsVitaminas() {
        assertThat(service.normalizarCategoria(null, "Omega 3 Fish Oil 1000mg 90 softgels")).isEqualTo("Vitaminas");
    }

    // ── Fallback peso/volumen: no-textil con indicador de peso ────────

    @Test
    void productoConPesoSinKeywordNoEsIndumentaria() {
        // productos sin keyword conocido pero con indicador de peso → Alimentos, no Indumentaria
        assertThat(service.normalizarCategoria(null, "GRANGER Chia Pudding 300g")).isNotEqualTo("Indumentaria");
    }

    // ══════════════════════════════════════════════════════════════════
    // subcategoria-field — resolverSubCategoria 3-tier classification
    // Covers FR-1 (Product 7th legacy ctor, task 5.2) and FR-3 (task 5.1)
    // ══════════════════════════════════════════════════════════════════

    // ── Task 5.2: Product 7th legacy constructor defaults subCategoria ──

    @Test
    void productLegacyConstructorDefaultsSubCategoriaToEmpty() {
        Product p = new Product("Sitio", "Nombre", 1000.0, null,
                "http://url", "http://img", "Remera", "hombre",
                List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
        assertThat(p.subCategoria()).isEqualTo("");
    }

    // ── Task 5.1: resolverSubCategoria tier-1 scenarios ───────────────

    @Test
    void resolverSubCategoriaTier1GorroWithNatacionKeywordReturnsNatacion() {
        // FR-3 scenario: Gorro + natación keyword → "natación"
        assertThat(service.resolverSubCategoria("Gorro de Natación Speedo", "Gorro"))
                .isEqualTo("natación");
    }

    @Test
    void resolverSubCategoriaTier1GorroWithNoKeywordReturnsInvierno() {
        // FR-3 scenario: Gorro with no specific keyword → "invierno" (Gorro default)
        assertThat(service.resolverSubCategoria("Gorro de Lana Azul", "Gorro"))
                .isEqualTo("invierno");
    }

    @Test
    void resolverSubCategoriaTier1MallaWithBikiniKeywordReturnsBikini() {
        // FR-3 scenario: Malla + bikini → "bikini"
        assertThat(service.resolverSubCategoria("Malla Bikini Triangular", "Malla"))
                .isEqualTo("bikini");
    }

    // ── Tier-2 transversal: Medias + hockey keyword in nombre ─────────

    @Test
    void resolverSubCategoriaTier2MediasWithHockeyReturnsHockey() {
        // FR-3 scenario: categoria has no tier-1 hockey match; tier-2 fires
        assertThat(service.resolverSubCategoria("Medias de Hockey Senior", "Medias"))
                .isEqualTo("hockey");
    }

    // ── Tier-2 accent-insensitive: "Padel" (no accent) → "pádel" ──────

    @Test
    void resolverSubCategoriaTier2AccentInsensitivePadelReturnsPadel() {
        // FR-3 scenario: accent-insensitive match
        assertThat(service.resolverSubCategoria("Remera Padel Club", "Remera"))
                .isEqualTo("pádel");
    }

    // ── Word-boundary guard: "espadela" must NOT match "padel" ────────

    @Test
    void resolverSubCategoriaWordBoundaryGuardPreventsEmbeddedMatch() {
        // FR-3 scenario: "padel" embedded mid-word must not false-positive
        assertThat(service.resolverSubCategoria("Raqueta espadela especial", "Accesorio Deportivo"))
                .isEqualTo("");
    }

    // ── Tier-2 running guard: skipped when categoria contains "Running" ─

    @Test
    void resolverSubCategoriaRunningSkippedWhenCategoriaContainsRunning() {
        // FR-3 scenario: running as tier-2 must be skipped for "Zapatilla Running"
        assertThat(service.resolverSubCategoria("Zapatilla Running Adidas", "Zapatilla Running"))
                .isEqualTo("");
    }

    // ── Tier-3 default: no match returns "" ───────────────────────────

    @Test
    void resolverSubCategoriaTier3DefaultReturnsEmpty() {
        // FR-3 scenario: neither tier-1 nor tier-2 matches → ""
        assertThat(service.resolverSubCategoria("Buzo Invierno Premium", "Buzo"))
                .isEqualTo("");
    }

    // ── Botas Snowboarding: gap probe ─────────────────────────────────

    @Test
    void resolverSubCategoriaBotasSnowboarding() {
        // Botas has no tier-1 rules; tier-2 sport fallback fires on "snowboarding"
        assertThat(service.resolverSubCategoria("Botas Snowboarding Nike", "Botas"))
                .isEqualTo("snowboarding");
    }

    // ══════════════════════════════════════════════════════════════════
    // gymrat-por-marca — sportswear brands are gym-eligible apparel even
    // without a training keyword in the name (user-confirmed: brand alone
    // qualifies torso/piernas for the Gym outfit builder). Tradeoff: these
    // items leave the Casual builder, since gym/casual are mutually
    // exclusive on the gymrat flag (OutfitService).
    // ══════════════════════════════════════════════════════════════════

    private Product normalizarUno(String nombre, String marca, String categoria) {
        Product in = new Product("Freres", nombre, 1000.0, null,
                "http://url", "http://img", categoria, "hombre",
                List.of(), Product.MlScore.EMPTY, marca);
        return service.normalizar(List.of(in)).get(0);
    }

    @Test
    void gymratMarcaDeportivaSinKeywordEsGymrat() {
        // "Remera Sportswear" has no training keyword; brand Nike alone qualifies it.
        assertThat(normalizarUno("Remera Sportswear", "Nike", "Remera").gymrat()).isTrue();
        assertThat(normalizarUno("Buzo Reverse Weave", "Champion", "Buzo").gymrat()).isTrue();
        assertThat(normalizarUno("Campera Rival Fleece", "Under Armour", "Campera").gymrat()).isTrue();
    }

    @Test
    void gymratMarcaNoDeportivaSinKeywordNoEsGymrat() {
        // Non-sportswear brand + no training keyword → not gymrat (stays casual-eligible).
        assertThat(normalizarUno("Remera Oversize Básica", "Lacoste", "Remera").gymrat()).isFalse();
    }

    @Test
    void gymratGuardCalzadoGanaSobreMarca() {
        // Calzado hard guard wins even for a sportswear brand — gymrat is ROPA only.
        assertThat(normalizarUno("Zapatillas Air Max", "Nike", "Zapatilla Running").gymrat()).isFalse();
    }
}
