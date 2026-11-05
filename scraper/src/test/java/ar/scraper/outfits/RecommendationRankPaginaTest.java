package ar.scraper.outfits;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Outfit Orchestration")
@Feature("Recommendations")
@Story("A page is the same slice of the full ranking")
@DisplayName("RecommendationService.rankPagina — bounded selection equals the full sort")
class RecommendationRankPaginaTest {

    private static final String[] MARCAS = {"Nike", "Adidas", "Puma", "Topper", ""};
    private static final String[] CATEGORIAS = {"Remera", "Zapatilla", "Short", "Buzo"};
    private static final String[] TENDENCIAS = {"estable", "subiendo", "bajando"};
    private static final List<List<String>> BADGES = List.of(
            List.of(), List.of("verified_deal"), List.of("all_time_low"), List.of("below_market"),
            List.of("verified_deal", "below_market"));

    private final RecommendationService service = new RecommendationService();

    private static Product producto(String url, String marca, String categoria, int scoreP, List<String> badges,
                                    boolean ofertaReal, String tendencia, String genero, String deporte) {
        return Product.builder()
                .sitio("TestSitio").nombre("Producto " + url).precio(10000).precioOriginal(null).url(url)
                .imagenUrl("https://img/test.jpg").categoria(categoria).genero(genero).talles(List.of())
                .ml(new Product.MlScore(scoreP, badges, ofertaReal, tendencia, scoreP, 0.0, "standard"))
                .marca(marca).rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).deporte(deporte).build();
    }

    private static Product elegible(String url, int scoreP) {
        return producto(url, "Nike", "Remera", scoreP, List.of(), false, "estable", "hombre", "");
    }

    private static List<Product> catalogoAleatorio(long semilla, int cantidad, int dominioDeScores) {
        Random azar = new Random(semilla);
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            String url = "https://t/" + azar.nextInt(Math.max(1, cantidad / 2 + 1));
            String genero = azar.nextInt(10) == 0 ? "infantil" : "hombre";
            String deporte = azar.nextInt(12) == 0 ? "futbol" : "";
            productos.add(producto(url, MARCAS[azar.nextInt(MARCAS.length)], CATEGORIAS[azar.nextInt(CATEGORIAS.length)],
                    azar.nextInt(dominioDeScores), BADGES.get(azar.nextInt(BADGES.size())), azar.nextBoolean(),
                    TENDENCIAS[azar.nextInt(TENDENCIAS.length)], genero, deporte));
        }
        return productos;
    }

    private List<Product> oraculo(List<Product> productos, OutfitService.FeedbackModel modelo) {
        List<Product> elegibles = new ArrayList<>(productos.stream()
                .filter(EligibilityPolicy::elegible)
                .filter(p -> !modelo.exclude().contains(OutfitService.FeedbackModel.keyOf(p)))
                .filter(p -> p.categoria() == null || !modelo.excludeCategoria().contains(p.categoria()))
                .toList());
        Comparator<Product> orden = Comparator
                .comparingDouble((Product p) -> -puntaje(p, modelo))
                .thenComparingInt(p -> p.ml().scoreP())
                .thenComparing(Product::url);
        elegibles.sort(orden);
        return elegibles;
    }

    private double puntaje(Product p, OutfitService.FeedbackModel modelo) {
        int likes = modelo.boostLikeCount().getOrDefault(OutfitService.FeedbackModel.keyOf(p), 0);
        double multiplicador = Math.min(1.0 + Math.min(likes, 5) * 0.15, 1.75);
        return service.baseMlScore(p) * multiplicador;
    }

    private static <T> List<T> rebanada(List<T> lista, int desde, int tamanio) {
        if (desde >= lista.size()) return List.of();
        return lista.subList(desde, (int) Math.min((long) desde + tamanio, lista.size()));
    }

    private static final int[] TAMANIOS = {1, 2, 3, 7, 10, 24, 50, 119, 120, 121, 1000, Integer.MAX_VALUE};
    private static final int[] DESDES = {0, 1, 5, 24, 59, 60, 119, 120, 121, 500, 100_000, Integer.MAX_VALUE};

    @Test
    @DisplayName("every page equals the same slice of the full ranking, for many sizes and offsets")
    void pageEqualsTheSliceOfTheFullRanking() {
        List<Product> productos = catalogoAleatorio(7, 120, 6);
        OutfitService.FeedbackModel modelo = OutfitService.FeedbackModel.empty();
        List<Product> completo = service.rank(productos, modelo);

        for (int tamanio : TAMANIOS) {
            for (int desde : DESDES) {
                RecommendationService.PaginaRankeada pagina = service.rankPagina(productos, modelo, desde, tamanio);

                assertThat(pagina.productos()).as("desde=%d tamanio=%d", desde, tamanio)
                        .containsExactlyElementsOf(rebanada(completo, desde, tamanio));
            }
        }
    }

    @Test
    @DisplayName("the full ranking equals an independent sort of the eligible products")
    void fullRankingEqualsAnIndependentSort() {
        List<Product> productos = catalogoAleatorio(11, 150, 6);
        OutfitService.FeedbackModel modelo = OutfitService.FeedbackModel.empty();

        assertThat(service.rank(productos, modelo)).containsExactlyElementsOf(oraculo(productos, modelo));
    }

    @Test
    @DisplayName("randomized catalogs with many tied scores page exactly like the full sort")
    void randomizedCatalogsWithTiesPageLikeTheFullSort() {
        for (long semilla = 1; semilla <= 60; semilla++) {
            Random azar = new Random(semilla);
            int cantidad = azar.nextInt(220);
            int dominioDeScores = 1 + azar.nextInt(4);
            List<Product> productos = catalogoAleatorio(semilla, cantidad, dominioDeScores);
            OutfitService.FeedbackModel modelo = OutfitService.FeedbackModel.empty();
            List<Product> esperado = oraculo(productos, modelo);

            for (int intento = 0; intento < 12; intento++) {
                int desde = azar.nextInt(cantidad + 5);
                int tamanio = 1 + azar.nextInt(60);

                RecommendationService.PaginaRankeada pagina = service.rankPagina(productos, modelo, desde, tamanio);

                assertThat(pagina.productos()).as("semilla=%d desde=%d tamanio=%d", semilla, desde, tamanio)
                        .containsExactlyElementsOf(rebanada(esperado, desde, tamanio));
                assertThat(pagina.total()).as("total, semilla=%d", semilla).isEqualTo(esperado.size());
            }
        }
    }

    @Test
    @DisplayName("products tied on every key keep their input order")
    void totalTiesKeepInputOrder() {
        List<Product> iguales = IntStream.range(0, 40)
                .mapToObj(i -> elegible("https://t/mismo", 50)).toList();

        List<Product> pagina = service.rankPagina(iguales, OutfitService.FeedbackModel.empty(), 10, 15).productos();

        assertThat(pagina).hasSize(15);
        for (int i = 0; i < pagina.size(); i++) {
            assertThat(pagina.get(i)).isSameAs(iguales.get(10 + i));
        }
    }

    @Test
    @DisplayName("total counts every eligible product, whatever the page asks for")
    void totalCountsAllEligible() {
        List<Product> productos = new ArrayList<>(catalogoAleatorio(3, 100, 5));
        long eligibles = productos.stream().filter(EligibilityPolicy::elegible).count();
        assertThat(eligibles).isLessThan(productos.size());

        for (int tamanio : new int[]{1, 5, 1000}) {
            for (int desde : new int[]{0, 50, 99, 5000}) {
                assertThat(service.rankPagina(productos, OutfitService.FeedbackModel.empty(), desde, tamanio).total())
                        .as("desde=%d tamanio=%d", desde, tamanio).isEqualTo((int) eligibles);
            }
        }
    }

    @Test
    @DisplayName("an offset past the end gives an empty page and the real total")
    void offsetPastTheEndIsEmpty() {
        List<Product> productos = List.of(elegible("https://t/1", 10), elegible("https://t/2", 20),
                elegible("https://t/3", 30));
        OutfitService.FeedbackModel modelo = OutfitService.FeedbackModel.empty();

        for (int desde : new int[]{3, 4, 100, Integer.MAX_VALUE}) {
            RecommendationService.PaginaRankeada pagina = service.rankPagina(productos, modelo, desde, 24);

            assertThat(pagina.productos()).as("desde=%d", desde).isEmpty();
            assertThat(pagina.total()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("a huge size and a huge offset do not overflow")
    void hugeSizeAndOffsetDoNotOverflow() {
        List<Product> productos = catalogoAleatorio(5, 80, 5);
        OutfitService.FeedbackModel modelo = OutfitService.FeedbackModel.empty();
        List<Product> esperado = oraculo(productos, modelo);

        assertThat(service.rankPagina(productos, modelo, 0, Integer.MAX_VALUE).productos())
                .containsExactlyElementsOf(esperado);
        assertThat(service.rankPagina(productos, modelo, Integer.MAX_VALUE, Integer.MAX_VALUE).productos()).isEmpty();
        assertThat(service.rankPagina(productos, modelo, Integer.MAX_VALUE, 1).productos()).isEmpty();
        assertThat(service.rankPagina(productos, modelo, Integer.MAX_VALUE - 1, 2).productos()).isEmpty();
        assertThat(service.rankPagina(productos, modelo, 5, Integer.MAX_VALUE).productos())
                .containsExactlyElementsOf(rebanada(esperado, 5, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("null and empty catalogs give an empty page, and null feedback means no feedback")
    void nullAndEmptyInputs() {
        assertThat(service.rankPagina(null, null, 0, 10).productos()).isEmpty();
        assertThat(service.rankPagina(null, null, 0, 10).total()).isZero();
        assertThat(service.rankPagina(List.of(), null, 0, 10).productos()).isEmpty();
        List<Product> productos = catalogoAleatorio(2, 30, 5);
        assertThat(service.rankPagina(productos, null, 0, 1000).productos())
                .containsExactlyElementsOf(oraculo(productos, OutfitService.FeedbackModel.empty()));
    }

    @Test
    @DisplayName("pair and category excludes remove products before they count and before they are ranked")
    void excludesAreRespected() {
        List<Product> productos = catalogoAleatorio(9, 150, 5);
        OutfitService.FeedbackModel modelo = new OutfitService.FeedbackModel(
                Set.of("Nike|Remera", "Adidas|Zapatilla", "|Short"), Map.of(), Set.of("Buzo"));
        List<Product> esperado = oraculo(productos, modelo);

        for (int tamanio : new int[]{1, 7, 24, 1000}) {
            for (int desde : new int[]{0, 3, 24, 60}) {
                RecommendationService.PaginaRankeada pagina = service.rankPagina(productos, modelo, desde, tamanio);

                assertThat(pagina.productos()).as("desde=%d tamanio=%d", desde, tamanio)
                        .containsExactlyElementsOf(rebanada(esperado, desde, tamanio));
                assertThat(pagina.total()).isEqualTo(esperado.size());
            }
        }
        assertThat(service.rank(productos, modelo))
                .noneMatch(p -> "Buzo".equals(p.categoria()))
                .noneMatch(p -> "Nike".equals(p.marca()) && "Remera".equals(p.categoria()))
                .noneMatch(p -> p.marca().isEmpty() && "Short".equals(p.categoria()));
    }

    @Test
    @DisplayName("likes boost a brand and category pair, and the boost stops growing at five likes")
    void boostIsRespectedAndCapped() {
        List<Product> productos = catalogoAleatorio(13, 150, 5);
        OutfitService.FeedbackModel conUnLike = new OutfitService.FeedbackModel(
                Set.of(), Map.of("Puma|Buzo", 1), Set.of());
        OutfitService.FeedbackModel conCincoLikes = new OutfitService.FeedbackModel(
                Set.of(), Map.of("Puma|Buzo", 5), Set.of());
        OutfitService.FeedbackModel conDiezLikes = new OutfitService.FeedbackModel(
                Set.of(), Map.of("Puma|Buzo", 10), Set.of());

        for (OutfitService.FeedbackModel modelo : List.of(conUnLike, conCincoLikes, conDiezLikes)) {
            List<Product> esperado = oraculo(productos, modelo);
            assertThat(service.rankPagina(productos, modelo, 0, 20).productos())
                    .containsExactlyElementsOf(rebanada(esperado, 0, 20));
            assertThat(service.rankPagina(productos, modelo, 20, 20).productos())
                    .containsExactlyElementsOf(rebanada(esperado, 20, 20));
        }
        assertThat(service.rank(productos, conCincoLikes)).containsExactlyElementsOf(service.rank(productos, conDiezLikes));
        assertThat(service.rank(productos, conUnLike)).isNotEqualTo(service.rank(productos, conCincoLikes));
    }

    @Test
    @DisplayName("a liked product outranks an otherwise identical one")
    void likedProductOutranksAnIdenticalOne() {
        Product conLike = producto("https://t/1", "Puma", "Buzo", 50, List.of(), false, "estable", "hombre", "");
        Product sinLike = producto("https://t/2", "Nike", "Buzo", 50, List.of(), false, "estable", "hombre", "");
        OutfitService.FeedbackModel modelo = new OutfitService.FeedbackModel(Set.of(), Map.of("Puma|Buzo", 2), Set.of());

        assertThat(service.rankPagina(List.of(sinLike, conLike), modelo, 0, 1).productos()).containsExactly(conLike);
        assertThat(service.rankPagina(List.of(sinLike, conLike), modelo, 1, 1).productos()).containsExactly(sinLike);
    }
}
