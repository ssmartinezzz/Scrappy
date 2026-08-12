package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.ml.FinanciacionCalculator;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que `/api/data` serializa por producto, contra una base real
 * (`sql-catalog-filtering`).
 *
 * <p>Estos tests vivían en {@code ApiControllerFinanciacionTest} con un
 * {@code DatabaseService} mockeado y el catálogo inyectado en el snapshot en
 * memoria. Desde que el endpoint consulta SQL, ese harness no puede sostenerlos:
 * el mock devuelve {@code null} y no hay catálogo. Se mudan acá en vez de
 * stubbearse, porque un test de filtros/serialización contra un mock verifica
 * a Mockito, no al endpoint. El CRUD de presets sigue en la clase original,
 * donde el mock SÍ es el colaborador correcto.</p>
 *
 * <p>Un cambio de comportamiento que estos tests ahora fijan: {@code senal} y
 * {@code senalFinanciacion} ya NO se leen de un valor precomputado que venía en
 * el snapshot — no se persisten en ninguna parte. Se calculan por request,
 * sobre los productos de la página. Por eso lo esperado se deriva del MISMO
 * calculador que usa producción, en vez de ser un número escrito a mano que
 * podría no tener nada que ver.</p>
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("/api/data serialization against a real catalog")
@DisplayName("ApiController — /api/data serializa contra la base")
class ApiControllerFinanciacionDataTest extends PostgresTestBase {

    private static final double RECARGO_PCT = 40.0;
    private static final int CUOTAS = 12;
    private static final double INFLACION_MENSUAL = 4.0;

    private ScraperService service;
    private InflacionService inflacionService;
    private ScraperConfig config;
    private DatabaseService db;
    private ApiController controller;
    private final List<Product> sembrados = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        inflacionService = mock(InflacionService.class);
        config = mock(ScraperConfig.class);
        // spy sobre el DatabaseService REAL: las consultas pegan contra Postgres
        // y además se puede verificar cuántas veces se llamó a un método, que es
        // lo que sostiene el guard de N+1 de abajo.
        db = spy(new DatabaseService(dataSource()));
        controller = new ApiController(service, inflacionService, config,
                mock(ar.scraper.aggregator.ResultAggregator.class), db,
                mock(GroupingService.class), mock(PythonRunner.class),
                mock(OutfitService.class), mock(RecommendationService.class));

        when(config.getMoneda()).thenReturn("ARS");
        when(inflacionService.getInflacionMensual()).thenReturn(INFLACION_MENSUAL);
    }

    @Test
    @DisplayName("La señal de financiación se calcula por producto con el preset activo")
    void senalFinanciacionSeCalculaConElPresetActivo() {
        sembrar(producto("https://site.com/a", 100000));
        activarPreset("12 cuotas / 40%");

        JsonNode prod0 = primerProducto(pedirData());
        JsonNode finan = prod0.path("senalFinanciacion");

        SenalFinanciacion esperada = FinanciacionCalculator.compute(
                100000, RECARGO_PCT, CUOTAS, INFLACION_MENSUAL / 100.0);
        assertThat(finan.path("senal").asText()).isEqualTo(esperada.senal());
        assertThat(finan.path("ahorroReal").asDouble()).isEqualTo(esperada.ahorroReal());
        assertThat(finan.path("vp").asDouble()).isEqualTo(esperada.vp());
        assertThat(finan.path("presetLabel").asText()).isEqualTo("12 cuotas / 40%");
    }

    @Test
    @DisplayName("El preset activo se resuelve UNA vez por request, no una por producto")
    void elPresetActivoSeResuelveUnaVezPorRequest() {
        sembrar(producto("https://site.com/a", 5000),
                producto("https://site.com/b", 6000),
                producto("https://site.com/c", 7000));
        activarPreset("Preset");

        pedirData();

        // Dos lecturas como techo: la del enricher y la del label de la respuesta.
        // Lo que este test impide es que crezcan con la cantidad de productos.
        verify(db, times(2)).cargarPresetActivo();
    }

    @Test
    @DisplayName("Sin preset activo la señal es sin_preset_activo y el label vacío")
    void sinPresetActivo() {
        sembrar(producto("https://site.com/a", 1000));

        JsonNode finan = primerProducto(pedirData()).path("senalFinanciacion");

        assertThat(finan.path("senal").asText()).isEqualTo("sin_preset_activo");
        assertThat(finan.path("presetLabel").asText()).isEmpty();
    }

    @Test
    @DisplayName("Los campos ML se serializan desde las columnas persistidas")
    void camposMlDesdeLasColumnas() {
        Product p = new Product("Sitio", "Producto ML", 10000, null, "https://site.com/ml", "img",
                "Remera", "unisex", List.of("M"),
                new Product.MlScore(80, List.of("all_time_low"), true, "bajando", 12, 0.5, "premium"),
                "Marca", "indumentaria", false, false,
                Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1);
        sembrar(p);

        JsonNode ml = primerProducto(pedirData()).path("ml");

        assertThat(ml.path("badge").asText()).isEqualTo("all_time_low");
        assertThat(ml.path("scoreP").asInt()).isEqualTo(80);
        assertThat(ml.path("ofertaReal").asBoolean()).isTrue();
        assertThat(ml.path("tendencia").asText()).isEqualTo("bajando");
        assertThat(ml.path("zScore").asDouble()).isEqualTo(0.5);
        assertThat(ml.path("segment").asText()).isEqualTo("premium");
    }

    @Test
    @DisplayName("Un pack serializa cantidadUnidades, esPack y precioUnitario")
    void packSerializaSusCampos() {
        sembrar(pack("https://site.com/pack", 15000, 3));

        JsonNode prod0 = primerProducto(pedirData());

        assertThat(prod0.path("cantidadUnidades").asInt()).isEqualTo(3);
        assertThat(prod0.path("esPack").asBoolean()).isTrue();
        assertThat(prod0.path("precioUnitario").asDouble()).isEqualTo(5000.0);
    }

    @Test
    @DisplayName("Un producto de una unidad no es pack")
    void unaUnidadNoEsPack() {
        sembrar(producto("https://site.com/single", 5000));

        JsonNode prod0 = primerProducto(pedirData());

        assertThat(prod0.path("cantidadUnidades").asInt()).isEqualTo(1);
        assertThat(prod0.path("esPack").asBoolean()).isFalse();
        assertThat(prod0.path("precioUnitario").asDouble()).isEqualTo(5000.0);
    }


    @Test
    @DisplayName("pack=true deja solo los packs")
    void packTrueFiltraSoloPacks() {
        sembrar(pack("https://site.com/packf1", 15000, 3),
                producto("https://site.com/singlef1", 5000));

        JsonNode prods = pedirData(true).path("productos");

        assertThat(prods).hasSize(1);
        assertThat(prods.get(0).path("url").asText()).isEqualTo("https://site.com/packf1");
        assertThat(prods.get(0).path("esPack").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("pack=false o ausente no filtra nada")
    void packFalseONuloNoFiltra() {
        sembrar(pack("https://site.com/packf2", 15000, 3),
                producto("https://site.com/singlef2", 5000));

        assertThat(pedirData(false).path("productos")).hasSize(2);
        assertThat(pedirData(null).path("productos")).hasSize(2);
    }

    @Test
    @DisplayName("packCount de /api/data cuenta sobre el catálogo COMPLETO, no sobre la página filtrada")
    void packCountEsDelCatalogoCompleto() {
        sembrar(pack("https://site.com/packf3", 15000, 3),
                pack("https://site.com/packf4", 9000, 2),
                producto("https://site.com/singlef3", 5000));

        JsonNode facets = pedirData(true).path("meta").path("facets");

        assertThat(facets.path("packCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("/api/facets también publica packCount")
    void facetsPublicaPackCount() {
        sembrar(pack("https://site.com/packf5", 15000, 3),
                producto("https://site.com/singlef4", 5000));

        assertThat(controller.facets().getBody().path("packCount").asInt()).isEqualTo(1);
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private JsonNode pedirData() {
        return pedirData(null);
    }

    private JsonNode pedirData(Boolean pack) {
        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", pack, null, null, null);
        return (JsonNode) resp.getBody();
    }

    private JsonNode primerProducto(JsonNode body) {
        assertThat(body).as("la respuesta trae cuerpo").isNotNull();
        return body.path("productos").get(0);
    }

    private void activarPreset(String label) {
        int id = db.crearPreset(label, RECARGO_PCT, CUOTAS);
        db.activarPreset(id);
    }

    private void sembrar(Product... productos) {
        sembrados.addAll(List.of(productos));
        db.upsertProductos(List.copyOf(sembrados));
    }

    private Product producto(String url, double precio) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY);
    }

    private Product pack(String url, double precioTotal, int unidades) {
        return new Product("Sitio", "Producto " + url, precioTotal, null, url, "img",
                "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, unidades);
    }
}
