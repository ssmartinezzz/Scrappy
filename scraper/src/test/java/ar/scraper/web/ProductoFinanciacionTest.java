package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.catalog.ProductKey;
import ar.scraper.catalog.ProductPort;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code GET /api/producto/{key}/financiacion}: live fetch, host guard, error mapping, cache and in-flight guard. */
@Epic("REST API")
@Feature("Catálogo")
@DisplayName("ProductoFinanciacionController — GET /api/producto/{key}/financiacion")
class ProductoFinanciacionTest {

    private static final String URL = "https://fullh4rd.com.ar/prod/18981/combo";
    private static final String KEY = ProductKey.of(URL);

    private ProductPort productos;
    private final AtomicInteger descargas = new AtomicInteger();
    private final AtomicReference<Instant> ahora = new AtomicReference<>(Instant.parse("2026-11-21T12:00:00Z"));
    private Function<String, Optional<String>> pagina;
    private ProductoFinanciacionController controller;

    @BeforeEach
    void setUp() throws IOException {
        productos = mock(ProductPort.class);
        String html;
        try (var in = getClass().getResourceAsStream("/fixtures/fullh4rd/producto-detalle.html")) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        pagina = url -> Optional.of(html);
        controller = new ProductoFinanciacionController(new FinanciacionProductoService(productos,
                new ScraperConfig(), url -> {
                    descargas.incrementAndGet();
                    return pagina.apply(url);
                }, ahora::get));
        existe("Fullh4rd", URL);
    }

    private void existe(String sitio, String url) {
        when(productos.obtenerProductoPorKey(ProductKey.of(url))).thenReturn(Optional.of(Product.builder()
                .sitio(sitio).nombre("Combo").precio(2_126_050).url(url).build()));
    }

    private static ApiException error(Runnable accion) {
        try {
            accion.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected an ApiException");
    }

    @Test
    @DisplayName("returns the parsed plans with the product identity and a UTC timestamp")
    void returnsPlans() {
        JsonNode data = Wire.data(controller.financiacion(KEY));

        assertThat(data.path("key").asText()).isEqualTo(KEY);
        assertThat(data.path("url").asText()).isEqualTo(URL);
        assertThat(data.path("sitio").asText()).isEqualTo("Fullh4rd");
        assertThat(data.path("consultadoAt").asText()).isEqualTo("2026-11-21T12:00:00Z");
        assertThat(data.path("precioContado").asDouble()).isEqualTo(2_126_050.0);
        assertThat(data.path("precioLista").asDouble()).isEqualTo(2_232_353.0);
        assertThat(data.path("stockWeb").asText()).isEqualTo("Stock alto en la web");
        assertThat(data.path("planes")).hasSize(7);
        JsonNode seis = data.path("planes").get(2);
        assertThat(seis.path("cuotas").asInt()).isEqualTo(6);
        assertThat(seis.path("montoCuota").asDouble()).isEqualTo(428_754.0);
        assertThat(seis.path("aproximado").asBoolean()).isTrue();
        assertThat(seis.path("medio").asText()).isEqualTo("Mercado Pago");
        assertThat(seis.path("sinInteres").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("404 no_encontrado for an unknown or blank key")
    void unknownProductIs404() {
        when(productos.obtenerProductoPorKey("nope")).thenReturn(Optional.empty());

        ApiException e = error(() -> controller.financiacion("nope"));
        assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.code()).isEqualTo("no_encontrado");
        assertThat(error(() -> controller.financiacion(" ")).code()).isEqualTo("no_encontrado");
        assertThat(descargas.get()).isZero();
    }

    @Test
    @DisplayName("422 sitio_sin_financiacion when the site has no detail parser, and nothing is fetched")
    void unsupportedSiteIs422() {
        existe("Otra Tienda", "https://otra.com/p/1");

        ApiException e = error(() -> controller.financiacion(ProductKey.of("https://otra.com/p/1")));
        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.code()).isEqualTo("sitio_sin_financiacion");
        assertThat(descargas.get()).isZero();
    }

    @Test
    @DisplayName("422 url_fuera_del_sitio when the product URL is on another host, and nothing is fetched")
    void foreignHostIs422() {
        for (String url : List.of("https://evil.example/prod/1", "https://fullh4rd.com.ar.evil.example/prod/1",
                "ftp://fullh4rd.com.ar/prod/1", "no es una url")) {
            existe("Fullh4rd", url);
            ApiException e = error(() -> controller.financiacion(ProductKey.of(url)));
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(e.code()).isEqualTo("url_fuera_del_sitio");
        }
        assertThat(descargas.get()).isZero();
    }

    @Test
    @DisplayName("503 financiacion_no_disponible when the solver returns nothing, and the failure is not cached")
    void solverDownIs503AndNotCached() {
        pagina = url -> Optional.empty();

        ApiException e = error(() -> controller.financiacion(KEY));
        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(e.code()).isEqualTo("financiacion_no_disponible");
        error(() -> controller.financiacion(KEY));
        assertThat(descargas.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("502 pagina_ilegible when the page has neither price nor installments")
    void unreadablePageIs502() {
        pagina = url -> Optional.of("<html><body>Un momento...</body></html>");

        ApiException e = error(() -> controller.financiacion(KEY));
        assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(e.code()).isEqualTo("pagina_ilegible");
    }

    @Test
    @DisplayName("a second call within the TTL is served from cache; after it, the page is fetched again")
    void cacheHitDoesNotRefetchUntilTtl() {
        controller.financiacion(KEY);
        ahora.set(ahora.get().plus(FinanciacionProductoService.TTL).minusSeconds(1));
        controller.financiacion(KEY);
        assertThat(descargas.get()).isEqualTo(1);

        ahora.set(ahora.get().plusSeconds(2));
        JsonNode data = Wire.data(controller.financiacion(KEY));

        assertThat(descargas.get()).isEqualTo(2);
        assertThat(data.path("consultadoAt").asText()).isEqualTo("2026-11-21T12:10:01Z");
    }

    @Test
    @DisplayName("concurrent callers of one key share a single fetch")
    void concurrentCallersShareOneFetch() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        Function<String, Optional<String>> real = pagina;
        pagina = url -> {
            dentro.countDown();
            try {
                soltar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return real.apply(url);
        };
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<JsonNode>> futuros = new ArrayList<>();
            for (int i = 0; i < 5; i++) futuros.add(pool.submit(() -> Wire.data(controller.financiacion(KEY))));
            assertThat(dentro.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(Duration.ofMillis(200));
            soltar.countDown();
            for (Future<JsonNode> f : futuros) assertThat(f.get(10, TimeUnit.SECONDS).path("planes")).hasSize(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(descargas.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent callers all get the 503 of the shared failed fetch")
    void sharedFailureReachesEveryCaller() throws Exception {
        CountDownLatch soltar = new CountDownLatch(1);
        pagina = url -> {
            try {
                soltar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        };
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<String>> futuros = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futuros.add(pool.submit(() -> error(() -> controller.financiacion(KEY)).code()));
            }
            Thread.sleep(Duration.ofMillis(200));
            soltar.countDown();
            for (Future<String> f : futuros) assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("financiacion_no_disponible");
        } finally {
            pool.shutdownNow();
        }
    }
}
