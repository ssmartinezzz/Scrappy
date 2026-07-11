package ar.scraper.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MlEnricher#enriquecer(List, JsonNode)}: wires
 * per-product ML scores (and an optional refined category) into a rebuilt
 * {@link Product}.
 */
@Epic("ML Pipeline")
@Feature("Score Enrichment")
@DisplayName("MlEnricher — wiring ML scores into Product")
class MlEnricherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void packProductPreservesCantidadUnidadesAfterEnrichment() throws Exception {
        // Regression for PR2: enriquecer() previously rebuilt Product via the
        // 16-arg legacy constructor, silently resetting cantidadUnidades to 1.
        Product pack = new Product(
                "Sitio", "Pack x3 Remeras", 15000.0, null, "https://site.com/pack",
                "", "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 3);

        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/pack": {
                            "composite": 60,
                            "badge": "precio_bajo",
                            "pctil": 40
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(pack), mlOutput);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(3);
        assertThat(result.get(0).esPack()).isTrue();
        assertThat(result.get(0).ml().badge()).isEqualTo("precio_bajo");
    }

    @Test
    void singleUnitProductStaysNonPackAfterEnrichment() throws Exception {
        Product single = new Product("Sitio", "Remera básica", 5000.0, null,
                "https://site.com/single", "", "Remeras", "unisex", List.of());

        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/single": { "composite": 50, "badge": "", "pctil": 50 }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(single), mlOutput);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(1);
        assertThat(result.get(0).esPack()).isFalse();
    }

    @Test
    void productWithoutMatchingScoreIsReturnedUnchanged() throws Exception {
        Product pack = new Product(
                "Sitio", "Pack x4 Medias", 8000.0, null, "https://site.com/sinmatch",
                "", "Medias", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 4);

        JsonNode mlOutput = MAPPER.readTree("""
                { "scores": {} }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(pack), mlOutput);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(pack);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(4);
    }

    // ── PR5: image-gender fill-in (text-wins invariant) ─────────────────────
    // Product.genero() is the 8th constructor arg; helper builds a minimal
    // Product with an explicit genero and a given URL for score lookup.
    private static Product productoConGenero(String genero, String url) {
        return new Product("Sitio", "Producto", 10000.0, null, url,
                "", "Remeras", genero, List.of());
    }

    @Test
    void nonBlankTextGeneroIsPreservedEvenWhenImageGenderPresent() throws Exception {
        Product p = productoConGenero("hombre", "https://site.com/a");
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/a": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "generoML": "mujer", "genImgConf": 0.99
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).genero()).isEqualTo("hombre");
    }

    @Test
    void blankTextGeneroIsFilledWithDecisiveHombreImageGender() throws Exception {
        Product p = productoConGenero("", "https://site.com/b");
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/b": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "generoML": "hombre", "genImgConf": 0.85
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).genero()).isEqualTo("hombre");
    }

    @Test
    void blankTextGeneroIsFilledWithDecisiveMujerImageGender() throws Exception {
        Product p = productoConGenero("", "https://site.com/c");
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/c": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "generoML": "mujer", "genImgConf": 0.85
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).genero()).isEqualTo("mujer");
    }

    @Test
    void unisexImageGenderNeverFillsBlankTextGenero() throws Exception {
        Product p = productoConGenero("", "https://site.com/d");
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/d": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "generoML": "unisex", "genImgConf": 0.99
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).genero()).isBlank();
    }

    @Test
    void belowThresholdImageGenderConfidenceLeavesGeneroBlank() throws Exception {
        Product p = productoConGenero("", "https://site.com/e");
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/e": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "generoML": "hombre", "genImgConf": 0.79
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).genero()).isBlank();
    }

    @Test
    void missingImageGeneroLeavesGeneroBlank() throws Exception {
        Product p = productoConGenero("", "https://site.com/f");
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/f": { "composite": 50, "badge": "", "pctil": 50 }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).genero()).isBlank();
    }

    @Test
    void categoryRefinementStillAppliesAlongsideGenderFillIn() throws Exception {
        Product p = productoConGenero("", "https://site.com/g");
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/g": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "categoriaML": "Zapatillas", "catMLConf": 0.9,
                            "generoML": "mujer", "genImgConf": 0.85
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).categoria()).isEqualTo("Zapatillas");
        assertThat(result.get(0).genero()).isEqualTo("mujer");
    }

    // ── T5.5/T5.6: image-derived VisualAttrs enrichment ─────────────────────
    // scores[url]'s fit/print/neckline/color keys are Spanish values already
    // remapped by ml_embeddings.py's classify()/dominant_color() + PR4's
    // ml_pipeline.py score-key remapping (estampado->print, escote->neckline,
    // color_dominante->color) — MlEnricher applies them verbatim, no further
    // re-mapping in Java.

    @Test
    void visualAttrsPopulatedVerbatimFromFitPrintNecklineColorScoreKeys() throws Exception {
        Product p = new Product("Sitio", "Buzo con capucha", 20000.0, null,
                "https://site.com/visual-h", "", "Buzos", "unisex", List.of());
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/visual-h": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "fit": "oversize", "print": "estampado",
                            "neckline": "capucha", "color": "gris"
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        Product.VisualAttrs visual = result.get(0).visual();
        assertThat(visual.fit()).isEqualTo("oversize");
        assertThat(visual.estampado()).isEqualTo("estampado");
        assertThat(visual.escote()).isEqualTo("capucha");
        assertThat(visual.colorDominante()).isEqualTo("gris");
    }

    @Test
    void missingVisualAttrScoreKeysDefaultToEmptyStringsWhenNoPriorVisual() throws Exception {
        // No prior visual() to preserve (defaults to VisualAttrs.EMPTY) AND
        // the score entry carries no fit/print/neckline/color keys at all —
        // there is no signal anywhere, so the result stays empty.
        Product p = new Product("Sitio", "Remera lisa", 8000.0, null,
                "https://site.com/visual-i", "", "Remeras", "unisex", List.of());
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/visual-i": { "composite": 50, "badge": "", "pctil": 50 }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        Product.VisualAttrs visual = result.get(0).visual();
        assertThat(visual.fit()).isEmpty();
        assertThat(visual.estampado()).isEmpty();
        assertThat(visual.escote()).isEmpty();
        assertThat(visual.colorDominante()).isEmpty();
    }

    @Test
    void missingVisualAttrScoreKeysPreserveExistingNonBlankVisual() throws Exception {
        // RELY-001 regression: a score entry missing the fit/print/neckline/
        // color keys entirely (this run's ML pass didn't gate this URL into
        // the image-fallback subset — see ml_pipeline.py's needs_image_fallback
        // cap) must NOT wipe a previously-persisted non-blank visual back to
        // "". Mirrors ml_embeddings.py's additive invariant
        // (ml_embeddings.py:660-676: "This CLI must only ever ADD signal,
        // never remove it").
        Product.VisualAttrs visualPrevio = new Product.VisualAttrs("oversize", "estampado", "capucha", "gris");
        Product p = new Product(
                "Sitio", "Remera lisa", 8000.0, null, "https://site.com/visual-i",
                "", "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "",
                visualPrevio);
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/visual-i": { "composite": 50, "badge": "", "pctil": 50 }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).visual()).isEqualTo(visualPrevio);
    }

    @Test
    void blankVisualAttrScoreValuesDefaultToEmptyStringsWhenNoPriorVisual() throws Exception {
        // No prior visual() to preserve AND the score entry explicitly sends
        // blank strings for all four keys — no signal anywhere, stays empty.
        Product p = new Product("Sitio", "Campera", 25000.0, null,
                "https://site.com/visual-j", "", "Camperas", "unisex", List.of());
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/visual-j": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "fit": "", "print": "", "neckline": "", "color": ""
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        Product.VisualAttrs visual = result.get(0).visual();
        assertThat(visual.fit()).isEmpty();
        assertThat(visual.estampado()).isEmpty();
        assertThat(visual.escote()).isEmpty();
        assertThat(visual.colorDominante()).isEmpty();
    }

    @Test
    void blankVisualAttrScoreValuesPreserveExistingNonBlankVisual() throws Exception {
        // RELY-001 regression: a score entry that explicitly sends blank
        // strings for fit/print/neckline/color (this run's ML pass abstained
        // for this URL) must NOT wipe a previously-persisted non-blank visual.
        Product.VisualAttrs visualPrevio = new Product.VisualAttrs("regular", "liso", "con cuello", "azul");
        Product p = new Product(
                "Sitio", "Campera", 25000.0, null, "https://site.com/visual-j",
                "", "Camperas", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "",
                visualPrevio);
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/visual-j": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "fit": "", "print": "", "neckline": "", "color": ""
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        assertThat(result.get(0).visual()).isEqualTo(visualPrevio);
    }

    @Test
    void nonBlankVisualAttrScoreValuesOverrideExistingVisualPerField() throws Exception {
        // RELY-001: a REAL signal (non-blank score value) still updates the
        // field, even when a different prior value existed — this is additive
        // fill/refresh, not a frozen-after-first-write lock. Verified per-field
        // (fit refreshed, others fall back to prior since blank/missing here).
        Product.VisualAttrs visualPrevio = new Product.VisualAttrs("regular", "liso", "con cuello", "azul");
        Product p = new Product(
                "Sitio", "Campera", 25000.0, null, "https://site.com/visual-k",
                "", "Camperas", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "",
                visualPrevio);
        JsonNode mlOutput = MAPPER.readTree("""
                {
                    "scores": {
                        "https://site.com/visual-k": {
                            "composite": 50, "badge": "", "pctil": 50,
                            "fit": "oversize"
                        }
                    }
                }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(p), mlOutput);

        Product.VisualAttrs visual = result.get(0).visual();
        assertThat(visual.fit()).isEqualTo("oversize");
        assertThat(visual.estampado()).isEqualTo("liso");
        assertThat(visual.escote()).isEqualTo("con cuello");
        assertThat(visual.colorDominante()).isEqualTo("azul");
    }

    @Test
    void productWithoutMatchingScorePreservesVisualAttrsUnchanged() throws Exception {
        // Regression for fashion-image-classification PR1: enriquecer() previously
        // rebuilt Product via the 18-arg legacy constructor, silently resetting
        // visual to VisualAttrs.EMPTY. This scenario (no matching score entry —
        // s.isMissingNode() early-return path) still returns `p` verbatim, so
        // visual must survive. When a score entry DOES exist, per-field values
        // are refreshed from that entry's fit/print/neckline/color keys ONLY
        // when non-blank (RELY-001) — a blank/missing key preserves the prior
        // value instead of wiping it (see missingVisualAttrScoreKeysPreserve*
        // and blankVisualAttrScoreValuesPreserve* above).
        Product.VisualAttrs visual = new Product.VisualAttrs("regular", "estampado", "capucha", "gris");
        Product conVisual = new Product(
                "Sitio", "Buzo con visual", 20000.0, null, "https://site.com/visual-ml",
                "", "Buzos", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "", visual);

        JsonNode mlOutput = MAPPER.readTree("""
                { "scores": {} }
                """);

        MlEnricher enricher = new MlEnricher();
        List<Product> result = enricher.enriquecer(List.of(conVisual), mlOutput);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).visual()).isEqualTo(visual);
    }
}
