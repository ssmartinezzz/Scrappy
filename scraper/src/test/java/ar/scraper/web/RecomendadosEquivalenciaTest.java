package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.feedback.FeedbackPort;
import ar.scraper.feedback.OutfitItemRow;
import ar.scraper.json.ProductJson;
import ar.scraper.model.Product;
import ar.scraper.outfits.EligibilityPolicy;
import ar.scraper.outfits.FeedbackModels;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.web.support.SujetoDePrueba;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The served page of /api/recomendados against a verbatim copy of the algorithm as it was before the regroup, the
 * double key build and the per-product allocation were removed, over seeded random catalogs full of ties, repeated and
 * blank urls, null fields, likes, dislikes and dismissed categories.
 */
class RecomendadosEquivalenciaTest {

    private static final String[] CATEGORIAS = {"Remera", "Buzo", "Zapatilla", "Pantalon", "", null, "Campera"};
    private static final String[] GENEROS = {null, "", " ", "unisex", "Unisex", " unisex ", "hombre", "Hombre", "mujer", "infantil"};
    private static final String[] PEDIDOS = {null, "", "  ", "unisex", "UNISEX", " unisex", "hombre", "Mujer", "otro"};
    private static final String[] FILTROS = {null, "", "Remera", "remera", "Buzo", "inexistente"};
    private static final String[] MARCAS = {null, "", "Nike", "Adidas", " Puma "};

    private ScraperService service;
    private FeedbackPort feedback;
    private RecomendadosController controller;
    private final RecommendationService recommendationService = new RecommendationService();

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        feedback = mock(FeedbackPort.class);
        SujetoDePrueba.entrar("ADMIN");
        controller = new RecomendadosController(service, feedback, recommendationService,
                new ar.scraper.security.ActorResolver());
    }

    @AfterEach
    void limpiar() {
        SujetoDePrueba.salir();
    }

    private static List<Product> catalogo(Random r, int n) {
        List<Product> lista = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String url = switch (r.nextInt(6)) {
                case 0 -> null;
                case 1 -> "";
                case 2 -> "https://t/dup" + r.nextInt(8);
                default -> "https://t/" + i;
            };
            Product.MlScore ml = r.nextInt(8) == 0 ? null : new Product.MlScore(r.nextInt(4) * 10,
                    List.of("verified_deal", "all_time_low", "below_market", "otro").subList(0, r.nextInt(5)),
                    r.nextBoolean(), new String[]{"estable", "", null, "sube"}[r.nextInt(4)], 50, 0.0, "standard");
            lista.add(Product.builder()
                    .sitio("Sitio" + r.nextInt(3)).nombre("Prenda " + i).precio(1000 + r.nextInt(5) * 100)
                    .url(url).imagenUrl("img").categoria(CATEGORIAS[r.nextInt(CATEGORIAS.length)])
                    .genero(GENEROS[r.nextInt(GENEROS.length)]).talles(List.of()).ml(ml)
                    .marca(MARCAS[r.nextInt(MARCAS.length)]).rubro("indumentaria")
                    .deporte(r.nextInt(15) == 0 ? "futbol" : "").build());
        }
        return lista;
    }

    private static List<OutfitItemRow> filas(Random r, List<Product> productos) {
        List<OutfitItemRow> rows = new ArrayList<>();
        int n = r.nextInt(6);
        for (int i = 0; i < n; i++) {
            String url = productos.get(r.nextInt(productos.size())).url();
            rows.add(new OutfitItemRow("catalog", url, r.nextBoolean(), "catalog"));
        }
        return rows;
    }

    @Test
    void theServedPageEqualsTheOldAlgorithmOnRandomCatalogs() {
        int comparados = 0;
        for (long semilla = 1; semilla <= 120; semilla++) {
            Random r = new Random(semilla);
            List<Product> productos = catalogo(r, 40 + r.nextInt(260));
            List<OutfitItemRow> rows = semilla % 3 == 0 ? List.of() : filas(r, productos);
            Set<String> descartadas = semilla % 4 == 0 ? Set.of() : Set.of("Buzo", "Campera");
            when(service.getLastResult()).thenReturn(new AggregatedResult(
                    productos, Map.of(), Map.of(), null, 0, 0, Map.of()));
            when(feedback.obtenerOutfitFeedback(any())).thenReturn(rows);
            when(feedback.obtenerCategoriaDismiss(any())).thenReturn(descartadas);
            OutfitService.FeedbackModel modelo = FeedbackModels.build(rows, productos, descartadas);

            for (String pedido : PEDIDOS) {
                for (String filtro : FILTROS) {
                    for (int[] pagina : new int[][]{{0, 24}, {0, 1}, {1, 7}, {2, 7}, {5, 3}, {40, 24}}) {
                        var respuesta = controller.recomendados(pagina[0], pagina[1], pedido, filtro);

                        var esperado = Viejo.pagina(productos, modelo, pedido, filtro, pagina[0], pagina[1]);
                        assertThat(respuesta.getBody().getData())
                                .as("semilla %d genero=%s categoria=%s pagina=%s", semilla, pedido, filtro,
                                        Arrays.toString(pagina))
                                .isEqualTo(esperado.items());
                        assertThat(respuesta.getBody().getPage().getTotal()).isEqualTo(esperado.total());
                        comparados++;
                    }
                }
            }
        }
        assertThat(comparados).isEqualTo(120 * PEDIDOS.length * FILTROS.length * 6);
    }

    @Test
    void anEmptyFeedbackNeverBuildsAPairKeyAndTheResultIsTheSame() {
        Random r = new Random(7);
        List<Product> productos = catalogo(r, 200);
        for (var vacio : List.of(OutfitService.FeedbackModel.empty(),
                new OutfitService.FeedbackModel(Set.of(), Map.of(), Set.of("Buzo")))) {
            for (int[] pagina : new int[][]{{0, 5}, {3, 10}, {0, Integer.MAX_VALUE / 2}}) {
                var nuevo = recommendationService.rankPagina(productos, vacio, pagina[0] * pagina[1], pagina[1]);
                var viejo = Viejo.rankPagina(productos, vacio, pagina[0] * pagina[1], pagina[1]);
                assertThat(nuevo.productos()).hasSameSizeAs(viejo.productos());
                for (int i = 0; i < viejo.productos().size(); i++) {
                    assertThat(nuevo.productos().get(i)).isSameAs(viejo.productos().get(i));
                }
                assertThat(nuevo.total()).isEqualTo(viejo.total());
            }
        }
    }

    private record Pagina(List<ObjectNode> items, long total) {}

    /** The algorithm as it was: regroup by category, then the rank that allocated a Puntuado and two keys per product. */
    private static final class Viejo {

        static Pagina pagina(List<Product> productos, OutfitService.FeedbackModel modelo, String genero,
                String categoria, int page, int size) {
            List<Product> candidatos = productos;
            if (StringUtils.isNotBlank(categoria)) {
                String c = categoria;
                candidatos = candidatos.stream().filter(p -> c.equalsIgnoreCase(p.categoria()))
                        .collect(Collectors.toList());
            }
            candidatos = broadenGenero(candidatos, genero);
            int paginaPedida = Math.max(0, page);
            int tamanio = Math.max(1, size);
            int desde = (int) Math.min((long) paginaPedida * tamanio, candidatos.size());
            var ranked = rankPagina(candidatos, modelo, desde, tamanio);
            List<ObjectNode> items = new ArrayList<>();
            PoliticaStock politica = PoliticaStock.porDefecto();
            for (Product p : ranked.productos()) {
                ObjectNode n = JsonNodeFactory.instance.objectNode();
                ProductJson.escribir(n, p, politica);
                items.add(n);
            }
            return new Pagina(items, ranked.total());
        }

        static List<Product> broadenGenero(List<Product> base, String generoSolicitado) {
            Map<String, List<Product>> byCategoria = base.stream()
                    .collect(Collectors.groupingBy(
                            p -> p.categoria() == null ? "" : p.categoria(),
                            LinkedHashMap::new, Collectors.toList()));
            List<Product> result = new ArrayList<>();
            for (Map.Entry<String, List<Product>> entry : byCategoria.entrySet()) {
                List<Product> productosCategoria = entry.getValue();
                List<Product> step1 = productosCategoria.stream()
                        .filter(p -> generoBridgeMatch(p, generoSolicitado)).collect(Collectors.toList());
                if (!step1.isEmpty()) {
                    result.addAll(step1);
                    continue;
                }
                List<Product> step2 = productosCategoria.stream()
                        .filter(p -> "unisex".equalsIgnoreCase(p.genero() != null ? p.genero().trim() : ""))
                        .collect(Collectors.toList());
                if (!step2.isEmpty()) {
                    result.addAll(step2);
                    continue;
                }
                result.addAll(productosCategoria);
            }
            return result;
        }

        static boolean generoBridgeMatch(Product p, String generoSolicitado) {
            String g = p.genero() != null ? p.genero().trim() : "";
            if (g.isEmpty()) return true;
            if ("unisex".equalsIgnoreCase(g)) return true;
            if (StringUtils.isBlank(generoSolicitado)) return true;
            if ("unisex".equalsIgnoreCase(generoSolicitado)) return true;
            return g.equalsIgnoreCase(generoSolicitado);
        }

        static RecommendationService.PaginaRankeada rankPagina(List<Product> productos,
                OutfitService.FeedbackModel modelo, int desde, int tamanio) {
            RecommendationService servicio = new RecommendationService();
            Set<String> exclude = modelo.exclude();
            Set<String> excludeCategoria = modelo.excludeCategoria();
            Map<String, Integer> boostLikeCount = modelo.boostLikeCount();

            long necesarios = Math.max(1L, (long) desde + tamanio);
            PriorityQueue<Puntuado> mejores = new PriorityQueue<>(
                    (int) Math.min(necesarios, productos.size() + 1L), ORDEN.reversed());
            int total = 0;
            for (Product p : productos) {
                if (!EligibilityPolicy.elegible(p)) continue;
                if (exclude.contains(OutfitService.FeedbackModel.keyOf(p))) continue;
                if (p.categoria() != null && excludeCategoria.contains(p.categoria())) continue;

                Puntuado candidato = new Puntuado(p, -finalScore(servicio, p, boostLikeCount),
                        p.ml() != null ? p.ml().scoreP() : 50, StringUtils.defaultString(p.url()), total++);
                if (mejores.size() < necesarios) {
                    mejores.add(candidato);
                } else if (ORDEN.compare(candidato, mejores.peek()) < 0) {
                    mejores.poll();
                    mejores.add(candidato);
                }
            }
            Puntuado[] ordenados = mejores.toArray(new Puntuado[0]);
            Arrays.sort(ordenados, ORDEN);
            List<Product> pagina = new ArrayList<>();
            for (int i = desde; i < ordenados.length; i++) pagina.add(ordenados[i].producto());
            return new RecommendationService.PaginaRankeada(pagina, total);
        }

        private record Puntuado(Product producto, double scoreNegado, int scoreP, String url, int posicion) {}

        private static final Comparator<Puntuado> ORDEN = Comparator
                .comparingDouble(Puntuado::scoreNegado)
                .thenComparingInt(Puntuado::scoreP)
                .thenComparing(Puntuado::url)
                .thenComparingInt(Puntuado::posicion);

        private static double finalScore(RecommendationService servicio, Product p, Map<String, Integer> likes) {
            double base = servicio.baseMlScore(p);
            int n = likes.getOrDefault(OutfitService.FeedbackModel.keyOf(p), 0);
            double multiplier = Math.min(1.0 + Math.min(n, 5) * 0.15, 1.75);
            return base * multiplier;
        }
    }
}
