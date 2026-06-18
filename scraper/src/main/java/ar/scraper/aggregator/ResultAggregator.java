package ar.scraper.aggregator;

import ar.scraper.db.DatabaseService;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class ResultAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(ResultAggregator.class);

    private final NormalizerService normalizer;
    private final PythonRunner      pythonRunner;
    private final MlEnricher        mlEnricher;
    private final DatabaseService   db;

    // Estado del último run — leído por ScraperService sin inyección circular
    private volatile JsonNode lastMlOutput     = null;
    private volatile int      lastCatRefinadas = 0;

    public ResultAggregator(NormalizerService normalizer,
                            PythonRunner      pythonRunner,
                            MlEnricher        mlEnricher,
                            DatabaseService   db) {
        this.normalizer   = normalizer;
        this.pythonRunner = pythonRunner;
        this.mlEnricher   = mlEnricher;
        this.db           = db;
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    public JsonNode             getLastMlOutput()    { return lastMlOutput; }
    public void                 setLastMlOutput(JsonNode n) { lastMlOutput = n; }
    public void                 clearMlOutput()      { this.lastMlOutput = null; }
    public int                  getLastCatRefinadas(){ return lastCatRefinadas; }
    public MlEnricher           getMlEnricher()      { return mlEnricher; }
    public PythonRunner         getPythonRunner()    { return pythonRunner; }

    // ─── Records ─────────────────────────────────────────────────────────────

    public record Facets(
            Map<String, Long> talles,
            Map<String, Long> generos,
            Map<String, Long> categorias,
            Map<String, Long> marcas,
            Map<String, Long> badges
    ) {}

    public record AggregatedResult(
            List<Product>        productos,
            Map<String, Integer> conteoPorSitio,
            Map<String, String>  erroresPorSitio,
            Facets               facets,
            double               minPrecio,
            double               maxPrecio
    ) {}

    // ─── Aggregation ─────────────────────────────────────────────────────────

    public AggregatedResult agregar(List<ScrapeResult> resultados, boolean forceRetrain) {
        Map<String, Integer> conteo  = new LinkedHashMap<>();
        Map<String, String>  errores = new LinkedHashMap<>();
        List<Product>        todos   = new ArrayList<>();

        for (ScrapeResult r : resultados) {
            conteo.put(r.sitio(), r.productos().size());
            if (!r.exitoso()) errores.put(r.sitio(), r.error());
            todos.addAll(r.productos());
        }

        // Dedup por sitio + nombre normalizado
        Map<String, Product> deduped = new LinkedHashMap<>();
        for (Product p : todos) {
            String key = p.sitio() + "||" + p.nombre().toLowerCase().trim();
            deduped.putIfAbsent(key, p);
        }
        List<Product> sorted = deduped.values().stream()
                .sorted(Comparator.comparingDouble(Product::precio))
                .collect(Collectors.toList());

        // Normalización con reglas Java
        List<Product> normalizados = normalizer.normalizar(sorted);

        // Pipeline ML Python (scoring + refinement de categorías)
        String prodJson = mlEnricher.serializarProductos(normalizados);
        LOG.info("[AGG] Ejecutando pipeline ML (esto puede tomar hasta 2 minutos)...");
        JsonNode mlOut  = pythonRunner.ejecutar(prodJson);
        LOG.info("[AGG] Pipeline ML completado.");
        lastMlOutput    = mlOut;

        // Enriquecer con scores + categorías ML
        List<Product> enriquecidos = mlEnricher.enriquecer(normalizados, mlOut, db);

        // Persistir categorías refinadas — O(n) con mapa
        Map<String, String> catOriginal = new HashMap<>();
        for (Product p : normalizados)
            if (p.url() != null && !p.url().isBlank())
                catOriginal.put(p.url(), p.categoria() != null ? p.categoria() : "");

        int catRefinadas = 0;
        for (Product p : enriquecidos) {
            String pid = p.url();
            if (pid == null || pid.isBlank()) continue;
            String antes = catOriginal.get(pid);
            String ahora = p.categoria() != null ? p.categoria() : "";
            if (antes != null && !ahora.equals(antes)) {
                try { db.actualizarCategoria(pid, ahora); catRefinadas++; }
                catch (Exception ignored) {}
            }
        }
        lastCatRefinadas = catRefinadas;
        LOG.info("[ML] Categorías persistidas en DB: {}", catRefinadas);

        // Facets, stats, persistencia
        Facets facets = calcularFacets(enriquecidos);
        double minP   = enriquecidos.isEmpty() ? 0 : enriquecidos.get(0).precio();
        double maxP   = enriquecidos.isEmpty() ? 0 : enriquecidos.get(enriquecidos.size()-1).precio();

        db.upsertProductos(enriquecidos);
        db.guardarMlOutput(mlOut);
        if (mlOut != null && !mlOut.path("categoriaStats").isMissingNode())
            db.guardarCategoriaStats(mlOut.path("categoriaStats"));

        LOG.info("Agregacion: {} brutos -> {} unicos (normalizado+ML)", todos.size(), enriquecidos.size());

        // Entrenamiento background post-scraping
        String dbPath = System.getProperty("user.dir") + java.io.File.separator + "scraper.db";
        LOG.info("[AGG] Lanzando entrenamiento del modelo en background...");
        pythonRunner.entrenarEnBackground(dbPath, forceRetrain);

        return new AggregatedResult(enriquecidos, conteo, errores, facets, minP, maxP);
    }

    public AggregatedResult agregar(List<ScrapeResult> resultados) {
        return agregar(resultados, false);
    }

    // ─── Facets ──────────────────────────────────────────────────────────────

    public static Facets calcularFacets(List<Product> productos) {
        Map<String, Long> talles = new LinkedHashMap<>();
        for (Product p : productos) {
            if (p.talles() != null)
                for (String t : p.talles())
                    if (!t.isBlank()) talles.merge(t, 1L, Long::sum);
        }
        talles = sortTalles(talles);

        Map<String, Long> generos = new LinkedHashMap<>();
        for (Product p : productos) {
            String g = p.genero() != null ? p.genero().trim().toLowerCase() : "";
            if (!g.isBlank()) generos.merge(g, 1L, Long::sum);
        }

        Map<String, Long> categorias = new LinkedHashMap<>();
        for (Product p : productos) {
            String cat = p.categoria() != null ? p.categoria().trim() : "";
            if (!cat.isBlank()) {
                String key = Character.toUpperCase(cat.charAt(0)) + cat.substring(1).toLowerCase();
                categorias.merge(key, 1L, Long::sum);
            }
        }
        categorias = categorias.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> marcas = new LinkedHashMap<>();
        for (Product p : productos) {
            String m = p.marca() != null ? p.marca().trim() : "";
            if (!m.isBlank()) marcas.merge(m, 1L, Long::sum);
        }
        marcas = marcas.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> badges = new LinkedHashMap<>();
        for (Product p : productos) {
            String b = (p.ml() != null && p.ml().badge() != null) ? p.ml().badge().trim() : "";
            if (!b.isBlank()) badges.merge(b, 1L, Long::sum);
        }

        return new Facets(talles, generos, categorias, marcas, badges);
    }

    private static Map<String, Long> sortTalles(Map<String, Long> talles) {
        List<String> orden = List.of("XS","S","M","L","XL","XXL","XXXL","3XL","4XL");
        List<String> conocidos = new ArrayList<>(), numericos = new ArrayList<>(), resto = new ArrayList<>();
        for (String t : talles.keySet()) {
            String u = t.toUpperCase();
            if (orden.contains(u)) conocidos.add(t);
            else if (t.matches("\\d+(\\.\\d+)?")) numericos.add(t);
            else resto.add(t);
        }
        conocidos.sort(Comparator.comparingInt(t -> { int i = orden.indexOf(t.toUpperCase()); return i >= 0 ? i : 999; }));
        numericos.sort(Comparator.comparingDouble(Double::parseDouble));
        resto.sort(String.CASE_INSENSITIVE_ORDER);
        Map<String, Long> result = new LinkedHashMap<>();
        for (String t : conocidos) result.put(t, talles.get(t));
        for (String t : numericos) result.put(t, talles.get(t));
        for (String t : resto)     result.put(t, talles.get(t));
        return result;
    }

    // Normalización rápida sin ML
    public List<Product> normalizarSolo(List<Product> productos) {
        return normalizer.normalizar(productos);
    }

    public AggregatedResult fromDB(List<Product> productos) {
        Map<String, Integer> conteo = new LinkedHashMap<>();
        productos.forEach(p -> conteo.merge(p.sitio(), 1, Integer::sum));
        Facets facets = calcularFacets(productos);
        double minP = productos.isEmpty() ? 0 : productos.get(0).precio();
        double maxP = productos.isEmpty() ? 0 : productos.get(productos.size()-1).precio();
        return new AggregatedResult(productos, conteo, Map.of(), facets, minP, maxP);
    }
}
