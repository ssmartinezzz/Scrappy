package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 4 — the in-memory half.
 *
 * <p>The rule the whole slice turns on: a reader is isolated from the SCRAPE,
 * not from their own actions. The progressive rebuild is held back; a manual
 * soft-delete, an agent reclassification, a financing preset and a catalogue
 * wipe all reach the served snapshot at once.</p>
 */
@Epic("Persistence")
@Feature("Scrape run tracking")
@Story("Reader isolation — the in-memory snapshot")
@DisplayName("ScraperService — el snapshot servido durante una corrida")
class ScraperServiceSnapshotIsolationTest {

    private static final String VIEJO = "https://site.com/viejo";
    private static final String FRESCO = "https://site.com/fresco";

    private DatabaseService db;
    private ResultAggregator aggregator;

    private Product producto(String url, String categoria) {
        return new Product("Sitio", "Producto", 1000, null, url, "",
                categoria, "unisex", List.of("M"), Product.MlScore.EMPTY, "Marca",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, "Sub", null);
    }

    private AggregatedResult catalogo(Product... productos) {
        List<Product> lista = List.of(productos);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), 1000, 1000);
    }

    /** @param yaHuboCorrida whether a COMPLETED run exists — D6's suppression switch. */
    private ScraperService servicio(boolean yaHuboCorrida) throws Exception {
        db = Mockito.mock(DatabaseService.class);
        aggregator = Mockito.mock(ResultAggregator.class);
        Mockito.when(db.crearScrapeRun(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(7L);
        Mockito.when(db.startedAtDeRun(7L))
                .thenReturn(Optional.of(Instant.parse("2026-08-26T10:00:00Z")));
        Mockito.when(db.existeCorridaCompletada()).thenReturn(yaHuboCorrida);
        return new ScraperService(Mockito.mock(ScraperConfig.class), aggregator, db);
    }

    private void abrir(ScraperService service) {
        service.abrirRun(List.of(new ScraperConfig.SiteConfig("Sitio", "https://site.com", "indumentaria")));
    }

    // ── freezing ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sin corrida abierta se sirve el catálogo vivo")
    void sinCorridaSeSirveElVivo() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa")));

        assertThat(service.getLastResult().productos()).extracting(Product::url)
                .containsExactly(VIEJO);
    }

    @Test
    @DisplayName("durante la corrida el reconstruido progresivo NO se sirve")
    void laCorridaCongelaLoQueSeSirve() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa")));

        abrir(service);
        // Lo que hace fromDBParcial cuando termina un sitio, sitio por sitio.
        service.setLastResultParaTest(catalogo(producto(FRESCO, "Remera")));

        assertThat(service.getLastResult().productos()).extracting(Product::url)
                .as("el lector mira la foto previa a la corrida, no el catálogo a medio rearmar")
                .containsExactly(VIEJO);
    }

    @Test
    @DisplayName("al cerrar la corrida se sirve el catálogo nuevo")
    void alCerrarSeSirveElNuevo() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa")));

        abrir(service);
        service.setLastResultParaTest(catalogo(producto(FRESCO, "Remera")));
        service.cerrarRun("COMPLETED", 1);

        assertThat(service.getLastResult().productos()).extracting(Product::url)
                .containsExactly(FRESCO);
    }

    @Test
    @DisplayName("una corrida que falla también libera el snapshot")
    void alFallarTambienSeLibera() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa")));

        abrir(service);
        service.cerrarRun("ERROR", 0);
        service.setLastResultParaTest(catalogo(producto(FRESCO, "Remera")));

        assertThat(service.getLastResult().productos()).extracting(Product::url)
                .as("sin esto un scrape que revienta congela al lector para siempre")
                .containsExactly(FRESCO);
    }

    @Test
    @DisplayName("instalación fresca: sin catálogo previo la corrida no congela nada")
    void instalacionFrescaVeElProgreso() throws Exception {
        ScraperService service = servicio(false);

        abrir(service);
        service.setLastResultParaTest(catalogo(producto(FRESCO, "Remera")));

        assertThat(service.getLastResult())
                .as("congelar un null dejaría la pantalla vacía toda la primera corrida")
                .isNotNull();
        assertThat(service.getLastResult().productos()).extracting(Product::url)
                .containsExactly(FRESCO);
    }

    // ── the four write paths a user triggers mid-run ─────────────────────────

    @Test
    @DisplayName("un soft-delete manual llega al snapshot servido")
    void softDeleteLlegaAlSnapshot() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa"), producto(FRESCO, "Remera")));

        abrir(service);
        service.eliminarProductoDeMemoria(VIEJO);

        assertThat(service.getLastResult().productos()).extracting(Product::url)
                .containsExactly(FRESCO);
    }

    @Test
    @DisplayName("una reclasificación del agente llega al snapshot servido")
    void reclasificacionLlegaAlSnapshot() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa")));

        abrir(service);
        service.actualizarProductoEnMemoria(VIEJO, "Remera", null, null, null, null);

        assertThat(service.getLastResult().productos().get(0).categoria()).isEqualTo("Remera");
    }

    @Test
    @DisplayName("activar un preset de financiación llega al snapshot servido")
    void financiacionLlegaAlSnapshot() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa")));

        Product reenriquecido = producto(VIEJO, "Camisa financiada");
        FinanciacionEnricher enricher = Mockito.mock(FinanciacionEnricher.class);
        Mockito.when(enricher.enriquecer(Mockito.anyList())).thenReturn(List.of(reenriquecido));
        Mockito.when(aggregator.financiacionEnricher()).thenReturn(enricher);

        abrir(service);
        service.recomputarFinanciacion(aggregator);

        assertThat(service.getLastResult().productos().get(0).categoria())
                .as("el tercer camino de escritura, el que las tasks no nombran: "
                    + "el usuario lo dispara desde tres sitios de FinanciacionEndpoints")
                .isEqualTo("Camisa financiada");
    }

    @Test
    @DisplayName("borrar el catálogo entero llega al snapshot servido")
    void borrarElCatalogoLlegaAlSnapshot() throws Exception {
        ScraperService service = servicio(true);
        service.setLastResultParaTest(catalogo(producto(VIEJO, "Camisa")));

        abrir(service);
        service.clearLastResult();

        assertThat(service.getLastResult())
                .as("el CUARTO camino: DELETE /api/db/productos. Sin esto el lector "
                    + "sigue viendo un catálogo que ya no existe en ningún lado")
                .isNull();
    }

    // ── the SQL bound (D6) ───────────────────────────────────────────────────

    @Test
    @DisplayName("sin corrida abierta no hay cota: se sirve todo")
    void sinCorridaNoHayCota() throws Exception {
        ScraperService service = servicio(true);

        assertThat(service.cotaDeLectura()).isEmpty();
    }

    @Test
    @DisplayName("con corrida abierta la cota es su started_at persistido")
    void laCotaEsElArranquePersistido() throws Exception {
        ScraperService service = servicio(true);

        abrir(service);

        assertThat(service.cotaDeLectura())
                .as("el started_at que vale es el truncado que quedó en la base")
                .contains(Instant.parse("2026-08-26T10:00:00Z"));
    }

    @Test
    @DisplayName("sin ninguna corrida COMPLETED la cota queda suprimida durante la corrida")
    void sinCorridaCompletadaLaCotaSeSuprime() throws Exception {
        ScraperService service = servicio(false);

        abrir(service);

        assertThat(service.cotaDeLectura())
                .as("con la cota puesta, nada cumple touched_at < started_at y la "
                    + "primera corrida de una instalación nueva sirve una pantalla vacía")
                .isEmpty();
    }

    @Test
    @DisplayName("al cerrar la corrida la cota desaparece")
    void alCerrarDesapareceLaCota() throws Exception {
        ScraperService service = servicio(true);

        abrir(service);
        service.cerrarRun("COMPLETED", 1);

        assertThat(service.cotaDeLectura()).isEmpty();
    }
}
