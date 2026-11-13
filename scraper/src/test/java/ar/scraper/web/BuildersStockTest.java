package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.model.Product;
import ar.scraper.outfits.OutfitService;
import ar.scraper.pcs.PcBuilder;
import ar.scraper.security.ActorResolver;
import ar.scraper.web.support.SujetoDePrueba;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Builders assemble something to buy: a confirmed sold-out candidate is not eligible by default, however cheap.
 * Unknown stock stays eligible (CODE-5). {@code incluirSinStock=true} opts back in. Scoring is untouched: this is a
 * candidate filter.
 */
@DisplayName("Armadores — candidatos sin stock")
class BuildersStockTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");
    private static final PoliticaStock POLITICA =
            new PoliticaStock(Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7));

    private ScraperService service;

    @BeforeEach
    void setUp() {
        SujetoDePrueba.entrar("ADMIN");
        service = mock(ScraperService.class);
    }

    @AfterEach
    void limpiar() {
        SujetoDePrueba.salir();
    }

    private static Product producto(String nombre, double precio, String categoria, String url, Boolean enStock) {
        return Product.builder().sitio("S").nombre(nombre).precio(precio).url(url).imagenUrl("img")
                .categoria(categoria).genero("hombre").talles(List.of()).ml(Product.MlScore.EMPTY).marca("")
                .rubro("tecnologia").enStock(enStock)
                .stockChequeadoAt(enStock == null ? null : AHORA.minus(Duration.ofHours(2))).build();
    }

    private void publicar(Product... ps) {
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.of(ps), Map.of(), Map.of(), null, 0, 0));
    }

    // ── PC builder ───────────────────────────────────────────────────────

    private PcsController pcs() {
        return new PcsController(service, new PcBuilder(), null, null, new ActorResolver(), POLITICA);
    }

    // With presupuesto 0 ("sin presupuesto") the builder prefers the priciest part of a slot, so the sold-out
    // candidate is the 500k one: only the stock filter can keep it out of the build.
    private String urlDelCpu(boolean incluirSinStock) {
        var resp = pcs().builder(0, false, "", "", "", "", "", "", null, null, null, "", "", null, "", null, "",
                incluirSinStock);
        for (JsonNode pick : Wire.data(resp).get("picks")) {
            if ("cpu".equals(pick.get("slot").asText())) return pick.get("url").asText();
        }
        return null;
    }

    @Test
    @DisplayName("PC: the sold-out CPU the builder would otherwise prefer is skipped by default")
    void pcSkipsSoldOutPart() {
        publicar(producto("Procesador Amd Ryzen 9 7900 Am5", 500_000, "CPU", "https://t/sin", false),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/en", true));

        assertThat(urlDelCpu(false)).isEqualTo("https://t/en");
    }

    @Test
    @DisplayName("PC: incluirSinStock=true lets the sold-out CPU back in")
    void pcOptIn() {
        publicar(producto("Procesador Amd Ryzen 9 7900 Am5", 500_000, "CPU", "https://t/sin", false),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/en", true));

        assertThat(urlDelCpu(true)).isEqualTo("https://t/sin");
    }

    @Test
    @DisplayName("PC: unknown stock stays eligible")
    void pcUnknownStaysEligible() {
        publicar(producto("Procesador Amd Ryzen 9 7900 Am5", 500_000, "CPU", "https://t/desc", null),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/en", true));

        assertThat(urlDelCpu(false)).isEqualTo("https://t/desc");
    }

    @Test
    @DisplayName("PC: the 17-arg call keeps its meaning (exclude sold-out)")
    void pcLegacyOverloadExcludes() {
        publicar(producto("Procesador Amd Ryzen 9 7900 Am5", 500_000, "CPU", "https://t/sin", false),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/en", true));

        var resp = pcs().builder(0, false, "", "", "", "", "", "", null, null, null, "", "", null, "", null, "");

        assertThat(Wire.data(resp).get("picks").get(0).get("url").asText()).isEqualTo("https://t/en");
    }

    // ── Supplements ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Product> candidatosDeSuplementos(boolean incluirSinStock) {
        OutfitService outfits = mock(OutfitService.class);
        when(outfits.armarComboSuplementos(anyList(), anyDouble(), anySet(), anySet())).thenReturn(List.of());
        new SuplementosController(service, outfits, POLITICA).suplementosBuilder("Creatina", 0, "", incluirSinStock);
        ArgumentCaptor<List<Product>> c = ArgumentCaptor.forClass(List.class);
        verify(outfits).armarComboSuplementos(c.capture(), anyDouble(), anySet(), anySet());
        return c.getValue();
    }

    private void publicarSuplementos() {
        publicar(producto("Creatina A", 100, "Creatina", "https://t/sin", false),
                producto("Creatina B", 200, "Creatina", "https://t/en", true),
                producto("Creatina C", 300, "Creatina", "https://t/desc", null));
    }

    @Test
    @DisplayName("Supplements: sold-out rows never reach the combo by default; unknown ones do")
    void suplementosExcludeSoldOutByDefault() {
        publicarSuplementos();

        assertThat(candidatosDeSuplementos(false)).extracting(Product::url)
                .containsExactly("https://t/en", "https://t/desc");
    }

    @Test
    @DisplayName("Supplements: incluirSinStock=true passes everything")
    void suplementosOptIn() {
        publicarSuplementos();

        assertThat(candidatosDeSuplementos(true)).extracting(Product::url)
                .containsExactly("https://t/sin", "https://t/en", "https://t/desc");
    }

    // ── Outfits ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Product> candidatosDeOutfit(boolean incluirSinStock) {
        OutfitService outfits = mock(OutfitService.class);
        when(outfits.armarPorCategorias(anyList(), anyList(), anyDouble(), any(), any(), anySet(), anyBoolean(),
                anyList(), anyString()))
                .thenReturn(new OutfitService.OutfitBuilderResult(
                        List.of(), "hombre", 1, 0.0, false, List.of(), List.of(), null));
        var feedback = mock(ar.scraper.feedback.FeedbackPort.class);
        new OutfitsController(service, feedback, null, outfits, new ActorResolver(), POLITICA)
                .outfitsBuilder("Short", 1000, "hombre", "", "", false, "gym", incluirSinStock);
        ArgumentCaptor<List<Product>> c = ArgumentCaptor.forClass(List.class);
        verify(outfits).armarPorCategorias(c.capture(), anyList(), anyDouble(), any(), any(), anySet(), anyBoolean(),
                anyList(), anyString());
        return c.getValue();
    }

    private void publicarShorts() {
        publicar(producto("Short A", 100, "Short", "https://t/sin", false),
                producto("Short B", 200, "Short", "https://t/en", true),
                producto("Short C", 300, "Short", "https://t/desc", null));
    }

    @Test
    @DisplayName("Outfits: sold-out rows are not candidates by default")
    void outfitsExcludeSoldOutByDefault() {
        publicarShorts();

        assertThat(candidatosDeOutfit(false)).extracting(Product::url)
                .containsExactly("https://t/en", "https://t/desc");
    }

    @Test
    @DisplayName("Outfits: incluirSinStock=true passes everything")
    void outfitsOptIn() {
        publicarShorts();

        assertThat(candidatosDeOutfit(true)).hasSize(3);
    }
}
