package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.catalog.ProductKey;
import ar.scraper.catalog.ProductPort;
import ar.scraper.classification.SiteClassification;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.FullH4rdFinanciacion;
import ar.scraper.pages.FullH4rdFinanciacion.Detalle;
import ar.scraper.scrapers.FlareSolverrClient;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Live installment plans of one product, read from its own detail page. FlareSolverr spins a Chromium
 * per call, so results are cached per key and concurrent callers of a key share one fetch.
 */
@Service
public class FinanciacionProductoService {

    private static final Logger log = LoggerFactory.getLogger(FinanciacionProductoService.class);
    static final Duration TTL = Duration.ofMinutes(10);
    /** Well under the 120 s HTTP timeout of the MCP client. */
    private static final int SOLVER_TIMEOUT_MS = 60_000;

    private static final Map<String, Function<String, Detalle>> PARSERS = Map.of("fullh4rd", FullH4rdFinanciacion::parse);

    public record Consulta(Product producto, Instant consultadoAt, Detalle detalle) { }

    private record Vigente(Consulta consulta, Instant expira) { }

    private final ProductPort productos;
    private final ScraperConfig config;
    private final Function<String, Optional<String>> descargador;
    private final Supplier<Instant> reloj;
    private final Map<String, Vigente> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Consulta>> enVuelo = new ConcurrentHashMap<>();

    @Autowired
    public FinanciacionProductoService(ProductPort productos, ScraperConfig config) {
        this(productos, config, url -> descargarConFlareSolverr(config, url), Instant::now);
    }

    FinanciacionProductoService(ProductPort productos, ScraperConfig config,
                                Function<String, Optional<String>> descargador, Supplier<Instant> reloj) {
        this.productos = productos;
        this.config = config;
        this.descargador = descargador;
        this.reloj = reloj;
    }

    private static Optional<String> descargarConFlareSolverr(ScraperConfig config, String url) {
        String solver = config.getFlareSolverrUrl();
        if (StringUtils.isBlank(solver)) return Optional.empty();
        return new FlareSolverrClient(solver).pagina(url, SOLVER_TIMEOUT_MS).map(FlareSolverrClient.Pagina::html);
    }

    public Consulta consultar(String key) {
        Product producto = (StringUtils.isBlank(key) ? Optional.<Product>empty() : productos.obtenerProductoPorKey(key))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no_encontrado", "El producto no existe."));
        String sitioKey = SiteClassification.sitioKey(producto.sitio());
        Function<String, Detalle> parser = PARSERS.get(sitioKey);
        if (parser == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "sitio_sin_financiacion",
                    "El sitio " + producto.sitio() + " no tiene lector de financiación.");
        }
        verificarHost(producto, sitioKey);

        String clave = ProductKey.of(producto.url());
        Consulta cacheada = vigente(clave);
        if (cacheada != null) return cacheada;
        CompletableFuture<Consulta> propia = new CompletableFuture<>();
        CompletableFuture<Consulta> ajena = enVuelo.putIfAbsent(clave, propia);
        if (ajena != null) return esperar(ajena);
        try {
            // A fetch that finished between the cache read and the slot claim already left its answer.
            Consulta lista = vigente(clave);
            if (lista == null) lista = descargar(producto, parser, clave);
            propia.complete(lista);
            return lista;
        } catch (RuntimeException e) {
            propia.completeExceptionally(e);
            throw e;
        } finally {
            enVuelo.remove(clave, propia);
        }
    }

    private Consulta vigente(String clave) {
        Vigente v = cache.get(clave);
        return v != null && reloj.get().isBefore(v.expira()) ? v.consulta() : null;
    }

    private Consulta descargar(Product producto, Function<String, Detalle> parser, String clave) {
        String html = descargador.apply(producto.url()).orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "financiacion_no_disponible",
                "No se pudo leer la página del producto: el solver de Cloudflare no respondió."));
        Detalle detalle = parser.apply(html);
        if (detalle.precioContado() == null && detalle.planes().isEmpty()) {
            log.warn("Página de {} sin precio ni cuotas: ¿cambió el markup?", producto.url());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "pagina_ilegible",
                    "La página del producto no trae precio ni cuotas reconocibles.");
        }
        Instant ahora = reloj.get();
        Consulta consulta = new Consulta(producto, ahora, detalle);
        cache.values().removeIf(v -> !ahora.isBefore(v.expira()));
        cache.put(clave, new Vigente(consulta, ahora.plus(TTL)));
        return consulta;
    }

    private static Consulta esperar(CompletableFuture<Consulta> futuro) {
        try {
            return futuro.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException causa) throw causa;
            throw e;
        }
    }

    /** The URL comes from scraped data: never fetch a host other than the one configured for the site. */
    private void verificarHost(Product producto, String sitioKey) {
        String esperado = config.getSitiosActivos().stream()
                .filter(s -> s.nombre().equals(sitioKey))
                .map(s -> hostDe(s.url()))
                .findFirst().orElse(null);
        String real = hostDe(producto.url());
        if (esperado == null || real == null || !esperado.equalsIgnoreCase(real)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "url_fuera_del_sitio",
                    "La URL del producto no pertenece al sitio configurado.");
        }
    }

    private static String hostDe(String url) {
        try {
            URI uri = URI.create(StringUtils.defaultString(url).trim());
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            return web ? uri.getHost() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
