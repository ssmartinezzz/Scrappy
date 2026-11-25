package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.catalog.CatalogoActualizado;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.config.CacheConfig;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoPaginas;
import ar.scraper.web.cache.CuerposGzip;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gzip body of /api/mejores against a real Tomcat: only a real socket shows whether the container deflates a
 * body that already says it is gzip, and whether the CORS Vary survives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = MejoresGzipRealPortTest.Ctx.class,
        properties = {"server.compression.enabled=true", "spring.jackson.use-jackson2-defaults=true",
                "app.cors.allowed-origins=http://localhost:5173"})
@Epic("Performance")
@Feature("HTTP compression")
@DisplayName("/api/mejores memoized gzip on a real port")
class MejoresGzipRealPortTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class})
    @Import({CacheConfig.class, CorsConfig.class, CatalogoPaginas.class, CuerposGzip.class, RespuestaGzip.class,
            MarcasPicksController.class})
    static class Ctx {
        @Bean
        ScraperService service() {
            return mock(ScraperService.class);
        }

        @Bean
        PoliticaStock politicaStock() {
            return PoliticaStock.porDefecto();
        }

        @Bean
        CatalogoDerivadoCache derivados(ScraperService service, PoliticaStock politica) {
            return new CatalogoDerivadoCache(service, mock(GroupingService.class), politica);
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    ScraperService service;
    @Autowired
    CacheManager cacheManager;
    @Autowired
    ApplicationEventPublisher eventos;

    private long version = 100;

    @BeforeEach
    void catalogoInicial() {
        publicar(catalogo("A"));
    }

    private void publicar(AggregatedResult resultado) {
        when(service.getLastResult()).thenReturn(resultado);
        when(service.snapshotVersion()).thenReturn(++version);
        eventos.publishEvent(new CatalogoActualizado(version));
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
    }

    private static AggregatedResult catalogo(String marcaDeLaCorrida) {
        List<Product> lista = new ArrayList<>();
        String[] categorias = {"Remera", "Zapatilla", "Campera", "Pantalon", "Buzo"};
        String[] rubros = {"indumentaria", "suplementos"};
        String[] generos = {"hombre", "mujer", "unisex"};
        for (int i = 0; i < 400; i++) {
            lista.add(Product.builder()
                    .sitio(i % 3 == 0 ? "Fuark" : "Sitio" + (i % 4)).nombre(categorias[i % 5] + " " + marcaDeLaCorrida + " modelo ñandú " + i)
                    .precio(1000 + (i * 37) % 9000).precioOriginal(null)
                    .url("https://s/" + marcaDeLaCorrida + "/" + i).imagenUrl("img" + i)
                    .categoria(categorias[i % 5]).genero(generos[i % 3])
                    .talles(List.of("M")).ml(Product.MlScore.EMPTY).marca(i % 3 == 0 ? "Fuark" : "Marca" + (i % 7)).rubro(rubros[i % 2])
                    .gymrat(i % 3 == 0).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                    .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).subCategoria("")
                    .visual(Product.VisualAttrs.EMPTY).build());
        }
        return new AggregatedResult(lista, Map.of(), Map.of(), ResultAggregator.calcularFacets(lista), 0, 0);
    }

    private HttpResponse<byte[]> get(String path, String... cabeceras) throws Exception {
        var pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        for (int i = 0; i < cabeceras.length; i += 2) pedido.header(cabeceras[i], cabeceras[i + 1]);
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] descomprimir(byte[] gz) throws Exception {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }

    private static List<String> tokens(HttpResponse<?> r, String cabecera) {
        return r.headers().allValues(cabecera).stream().flatMap(v -> java.util.Arrays.stream(v.split(",")))
                .map(String::trim).toList();
    }

    @Test
    @DisplayName("the gunzipped body is byte-identical to the identity body, for rubro, gymrat and genero")
    void decodedBodyEqualsIdentityBody() throws Exception {
        for (String consulta : List.of("", "?rubro=indumentaria", "?rubro=suplementos", "?gymrat=true", "?genero=mujer",
                "?rubro=indumentaria&gymrat=true&genero=hombre")) {
            var plano = get("/api/mejores" + consulta);
            var gz = get("/api/mejores" + consulta, "Accept-Encoding", "gzip");

            assertThat(plano.statusCode()).as(consulta).isEqualTo(200);
            assertThat(plano.body().length).as(consulta).isGreaterThan(2_048);
            assertThat(plano.headers().firstValue("Content-Encoding")).as(consulta).isEmpty();
            assertThat(gz.statusCode()).as(consulta).isEqualTo(200);
            assertThat(gz.headers().allValues("Content-Encoding")).as(consulta).containsExactly("gzip");
            assertThat(descomprimir(gz.body())).as(consulta).isEqualTo(plano.body());
        }
    }

    @Test
    @DisplayName("the keys really differ, so the equality above is not one body compared with itself")
    void keysProduceDifferentBodies() throws Exception {
        var a = get("/api/mejores?rubro=indumentaria", "Accept-Encoding", "gzip");
        var b = get("/api/mejores?gymrat=true", "Accept-Encoding", "gzip");

        assertThat(descomprimir(a.body())).isNotEqualTo(descomprimir(b.body()));
    }

    @Test
    @DisplayName("Content-Type matches the identity response; Content-Length is the gzip length; Vary names Accept-Encoding")
    void headersAreEquivalent() throws Exception {
        var plano = get("/api/mejores");
        var gz = get("/api/mejores", "Accept-Encoding", "gzip");

        assertThat(gz.headers().firstValue("Content-Type")).isEqualTo(plano.headers().firstValue("Content-Type"));
        assertThat(gz.headers().firstValue("Content-Length")).hasValue(String.valueOf(gz.body().length));
        assertThat(tokens(gz, "Vary")).anyMatch("Accept-Encoding"::equalsIgnoreCase);
    }

    @Test
    @DisplayName("a client that refuses gzip, or sends no header, gets the identity body")
    void clientWithoutGzipGetsIdentity() throws Exception {
        var plano = get("/api/mejores");

        for (String cabecera : List.of("identity", "gzip;q=0", "br")) {
            var r = get("/api/mejores", "Accept-Encoding", cabecera);
            assertThat(r.headers().firstValue("Content-Encoding")).as(cabecera).isEmpty();
            assertThat(r.body()).as(cabecera).isEqualTo(plano.body());
        }
    }

    @Test
    @DisplayName("a client that does not accept JSON gets 406 with or without gzip")
    void nonJsonAcceptIsRefused() throws Exception {
        assertThat(get("/api/mejores", "Accept", "text/html").statusCode()).isEqualTo(406);
        assertThat(get("/api/mejores", "Accept", "text/html", "Accept-Encoding", "gzip").statusCode()).isEqualTo(406);
    }

    @Test
    @DisplayName("a CORS request keeps Vary: Origin next to Accept-Encoding and its Access-Control headers")
    void corsVarySurvives() throws Exception {
        var r = get("/api/mejores", "Accept-Encoding", "gzip", "Origin", "http://localhost:5173");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        assertThat(r.headers().allValues("Content-Encoding")).containsExactly("gzip");
        assertThat(tokens(r, "Vary")).anyMatch("Origin"::equalsIgnoreCase).anyMatch("Accept-Encoding"::equalsIgnoreCase);
    }

    @Test
    @DisplayName("without a snapshot the answer is 204 with no body and no encoding")
    void noSnapshotIs204() throws Exception {
        when(service.getLastResult()).thenReturn(null);

        var r = get("/api/mejores", "Accept-Encoding", "gzip");

        assertThat(r.statusCode()).isEqualTo(204);
        assertThat(r.body()).isEmpty();
        assertThat(r.headers().firstValue("Content-Encoding")).isEmpty();
    }

    @Test
    @DisplayName("a new snapshot version serves the new body, not the memoized one")
    void newSnapshotServesNewBody() throws Exception {
        var antes = descomprimir(get("/api/mejores", "Accept-Encoding", "gzip").body());

        publicar(catalogo("B"));
        var gz = get("/api/mejores", "Accept-Encoding", "gzip");
        var plano = get("/api/mejores");

        assertThat(descomprimir(gz.body())).isNotEqualTo(antes).isEqualTo(plano.body());
    }
}
