package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.feedback.FeedbackPort;
import ar.scraper.model.Product;
import ar.scraper.outfits.OutfitService;
import ar.scraper.pcs.PcBuild;
import ar.scraper.pcs.PcBuilder;
import ar.scraper.security.ActorResolver;
import ar.scraper.web.support.SujetoDePrueba;
import ar.scraper.web.support.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Builder controllers - comma-separated query params")
class CsvQueryParamsTest {

    private static final String SPACED = " https://a/1 , ,https://b/2,, ";
    private static final Set<String> SPACED_SET = Set.of("https://a/1", "https://b/2");

    private ScraperService service;
    private OutfitService outfitService;
    private FeedbackPort feedback;

    @BeforeEach
    void setUp() {
        SujetoDePrueba.entrar("ADMIN");
        service = mock(ScraperService.class);
        outfitService = mock(OutfitService.class);
        feedback = mock(FeedbackPort.class);
        when(feedback.obtenerOutfitFeedback(any())).thenReturn(List.of());
        when(feedback.obtenerCategoriaDismiss(any())).thenReturn(Set.of());
    }

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    private AggregatedResult resultado(List<Product> productos) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(productos, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product conUrl(String url) {
        return Product.builder().sitio("S").nombre("n").precio(1).url(url).build();
    }

    private OutfitsController outfits() {
        return new OutfitsController(service, feedback, null, outfitService, new ActorResolver());
    }

    @Test
    void gymOutfitStripsAndDropsBlankExcluirEntries() {
        when(service.getLastResult()).thenReturn(resultado(List.of()));
        when(outfitService.armar(any(), any(), anyString(), any(), anyDouble(), any()))
                .thenReturn(new OutfitService.Outfit(List.of(), "hombre", false, 0.0, false));

        outfits().outfits("hombre", 0, SPACED, 0);

        verify(outfitService).armar(any(), any(), anyString(), any(), anyDouble(), eq(SPACED_SET));
    }

    @Test
    void gymOutfitBlankExcluirIsAnEmptyImmutableSet() {
        when(service.getLastResult()).thenReturn(resultado(List.of()));
        when(outfitService.armar(any(), any(), anyString(), any(), anyDouble(), any()))
                .thenReturn(new OutfitService.Outfit(List.of(), "hombre", false, 0.0, false));

        outfits().outfits("hombre", 0, "  ", 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(outfitService).armar(any(), any(), anyString(), any(), anyDouble(), captor.capture());
        assertThat(captor.getValue()).isEmpty();
        assertThat(captor.getValue().getClass().getName()).startsWith("java.util.ImmutableCollections");
    }

    @Test
    void builderKeepsKnownDistinctCategoriasInOrderAndStripsExcluirAndPin() {
        when(service.getLastResult()).thenReturn(resultado(List.of(
                conUrl("https://a/1"), conUrl(null), conUrl("https://b/2"), conUrl("https://b/2"))));
        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), any(), any(), anySet(),
                anyBoolean(), anyList(), anyString()))
                .thenReturn(new OutfitService.OutfitBuilderResult(
                        List.of(), "hombre", 1, 0.0, false, List.of(), List.of(), null));

        outfits().outfitsBuilder(" Short , ,Buzo,Short,Inexistente", 1000, "hombre", SPACED,
                " https://b/2 , ,https://nope,https://a/1,", false, "gym");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cats = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> excluir = ArgumentCaptor.forClass(Set.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Product>> pinned = ArgumentCaptor.forClass(List.class);
        verify(outfitService).armarPorCategorias(anyList(), cats.capture(), anyDouble(), any(), any(),
                excluir.capture(), anyBoolean(), pinned.capture(), anyString());
        assertThat(cats.getValue()).containsExactly("Short", "Buzo");
        assertThat(excluir.getValue()).isEqualTo(SPACED_SET);
        assertThat(pinned.getValue()).extracting(Product::url).containsExactly("https://b/2", "https://a/1");
    }

    @Test
    void builderWithOnlyUnknownCategoriasIs400AndNeverReachesTheService() {
        var resp = Wire.answer(() -> outfits().outfitsBuilder(" , ,Inexistente", 1000, "hombre", "", "", false, "gym"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(outfitService, never()).armarPorCategorias(any(), any(), anyDouble(), any(), any(), any(),
                anyBoolean(), any(), any());
    }

    @Test
    void suplementosStripsTiposAndExcluir() {
        when(service.getLastResult()).thenReturn(resultado(List.of()));
        when(outfitService.armarComboSuplementos(any(), anyDouble(), any(), any())).thenReturn(List.of());

        new SuplementosController(service, outfitService)
                .suplementosBuilder(" Proteína , ,Creatina,Creatina", 0, SPACED);

        verify(outfitService).armarComboSuplementos(any(), anyDouble(),
                eq(Set.of("Proteína", "Creatina")),
                eq(SPACED_SET));
    }

    @Test
    void suplementosWithOnlySeparatorsIs400() {
        when(service.getLastResult()).thenReturn(resultado(List.of()));

        var resp = Wire.answer(() -> new SuplementosController(service, outfitService)
                .suplementosBuilder(" , ,", 0, ""));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void pcsBuilderStripsExcluir() {
        when(service.getLastResult()).thenReturn(resultado(List.of()));
        PcBuilder pcBuilder = mock(PcBuilder.class);
        when(pcBuilder.armar(any(), anyDouble(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(new PcBuild(List.of(), List.of(), List.of(), 0, 0));

        new PcsController(service, pcBuilder, null, null, new ActorResolver())
                .builder(0, false, SPACED, "", "", "", "", "", null, null, null, "", "", null, "");

        verify(pcBuilder).armar(any(), anyDouble(), anyBoolean(), eq(SPACED_SET),
                any(), any(), any());
    }
}
