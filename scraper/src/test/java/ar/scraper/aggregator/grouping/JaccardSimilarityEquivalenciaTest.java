package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Equivalence guard for the {@link JaccardSimilarity} sub-grouping rewrite.
 *
 * <p>{@code subAgruparPorJaccard} recomputed {@code palabrasSignificativas(j)}
 * inside its inner loop, so a pre-group of k products tokenized ~k²/2 times
 * instead of k. Each tokenization is regex-heavy — {@code String.replaceAll}
 * and {@code String.matches} compile a fresh {@code Pattern} per token, per
 * call — and {@code jaccardSimilarity} allocated two throwaway {@code HashSet}s
 * per comparison on top of that. All of it recomputed on every request to
 * {@code /api/grupos}, including plain pagination clicks.</p>
 *
 * <p>The existing {@code JaccardSimilarityTest} and
 * {@code GroupingServiceCharacterizationTest} pin the semantics on hand-built
 * examples. This class adds what a rewrite actually needs: the original
 * implementations kept verbatim as an oracle, diffed against the shipped ones
 * over randomized corpora. Greedy assignment makes grouping order-sensitive —
 * which product seeds a group changes the outcome — so nothing short of a
 * full structural comparison would catch a subtle reordering.</p>
 */
@Epic("Aggregation & Grouping")
@Feature("Comparador multi-sitio")
@Story("Equivalencia del sub-agrupado Jaccard")
@DisplayName("JaccardSimilarity — la optimización no cambia ni un grupo")
class JaccardSimilarityEquivalenciaTest {

    private static final double JACCARD_THRESHOLD = 0.55;

    private final JaccardSimilarity jaccard = new JaccardSimilarity();

    // ─── Oracle: the pre-optimization implementations, verbatim ──────────────

    private List<List<Product>> subAgruparReferencia(List<Product> productos) {
        List<List<Product>> grupos = new ArrayList<>();
        boolean[] asignado = new boolean[productos.size()];

        for (int i = 0; i < productos.size(); i++) {
            if (asignado[i]) continue;
            List<Product> grupo = new ArrayList<>();
            grupo.add(productos.get(i));
            asignado[i] = true;
            Set<String> wordsI = jaccard.palabrasSignificativas(productos.get(i));

            for (int j = i + 1; j < productos.size(); j++) {
                if (asignado[j]) continue;
                if (productos.get(i).sitio().equals(productos.get(j).sitio())) continue;
                Set<String> wordsJ = jaccard.palabrasSignificativas(productos.get(j));
                if (jaccardReferencia(wordsI, wordsJ) >= JACCARD_THRESHOLD) {
                    grupo.add(productos.get(j));
                    asignado[j] = true;
                }
            }
            grupos.add(grupo);
        }
        return grupos;
    }

    private double jaccardReferencia(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> union        = new HashSet<>(a); union.addAll(b);
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    // ─── Corpus ──────────────────────────────────────────────────────────────

    private static final String[] SITIOS  = {"freres", "midway", "batuk", "vcp", "harvey"};
    private static final String[] MARCAS  = {"Nike", "Adidas", "Puma", "Topper", "Fila"};
    private static final String[] LINEAS  = {"Air Force", "Air Max", "Superstar", "Gazelle",
                                             "Suede Classic", "Oversize Basica", "Rompeviento"};
    private static final String[] COLORES = {"Negro", "Blanco", "Azul", "Gris", "Verde"};

    private Product producto(String sitio, String marca, String nombre) {
        return new Product(sitio, nombre, 1000, null,
                "https://" + sitio + "/" + nombre.hashCode(), "img", "Zapatillas", "unisex",
                List.of("M"), Product.MlScore.EMPTY, marca, "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "",
                Product.VisualAttrs.EMPTY);
    }

    private List<Product> corpus(Random rng, int n) {
        List<Product> productos = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String marca = MARCAS[rng.nextInt(MARCAS.length)];
            String nombre = marca + " " + LINEAS[rng.nextInt(LINEAS.length)] + " "
                          + COLORES[rng.nextInt(COLORES.length)] + " Talle " + (36 + rng.nextInt(10));
            productos.add(producto(SITIOS[rng.nextInt(SITIOS.length)], marca, nombre));
        }
        return productos;
    }

    private static List<List<String>> urls(List<List<Product>> grupos) {
        List<List<String>> out = new ArrayList<>();
        for (List<Product> g : grupos) out.add(g.stream().map(Product::url).toList());
        return out;
    }

    // ─── Equivalence ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("produce exactamente los mismos grupos, en el mismo orden, sobre corpus aleatorios")
    void mismosGruposEnCorpusAleatorios() {
        for (int semilla = 0; semilla < 15; semilla++) {
            List<Product> productos = corpus(new Random(semilla), 120);
            assertThat(urls(jaccard.subAgruparPorJaccard(productos)))
                    .as("semilla %d", semilla)
                    .isEqualTo(urls(subAgruparReferencia(productos)));
        }
    }

    @Test
    @DisplayName("equivalente también cuando casi todo es del mismo sitio")
    void equivalenteConUnSitioDominante() {
        Random rng = new Random(99);
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            String marca = MARCAS[rng.nextInt(MARCAS.length)];
            String sitio = i % 9 == 0 ? SITIOS[1 + rng.nextInt(SITIOS.length - 1)] : "freres";
            productos.add(producto(sitio, marca,
                    marca + " " + LINEAS[rng.nextInt(LINEAS.length)] + " " + COLORES[rng.nextInt(COLORES.length)]));
        }
        assertThat(urls(jaccard.subAgruparPorJaccard(productos)))
                .isEqualTo(urls(subAgruparReferencia(productos)));
    }

    @Test
    @DisplayName("equivalente con nombres que no dejan ninguna palabra significativa")
    void equivalenteConNombresSinPalabrasUtiles() {
        // Stop words, tokens de 1-2 letras y numeros puros: el set queda vacio,
        // y dos sets vacios dan similitud 1.0 (se agrupan si son de sitios distintos).
        List<Product> productos = List.of(
                producto("freres", "", "de la el 12"),
                producto("midway", "", "un a 7 los"),
                producto("batuk",  "", "Nike Air Force Negro"),
                producto("vcp",    "", "las 3 y de"));

        assertThat(urls(jaccard.subAgruparPorJaccard(productos)))
                .isEqualTo(urls(subAgruparReferencia(productos)));
    }

    @Test
    @DisplayName("equivalente con listas triviales")
    void equivalenteConListasTriviales() {
        assertThat(urls(jaccard.subAgruparPorJaccard(List.of())))
                .isEqualTo(urls(subAgruparReferencia(List.of())));

        List<Product> uno = List.of(producto("freres", "Nike", "Nike Air Force Negro"));
        assertThat(urls(jaccard.subAgruparPorJaccard(uno)))
                .isEqualTo(urls(subAgruparReferencia(uno)));
    }

    // ─── The similarity function itself ──────────────────────────────────────

    @Test
    @DisplayName("la similitud coincide con la fórmula original en todos los solapamientos")
    void laSimilitudCoincideConLaFormulaOriginal() {
        Random rng = new Random(5);
        List<String> vocabulario = List.of("nike", "air", "force", "max", "negro", "blanco",
                                           "urbana", "running", "clasica", "retro");
        for (int caso = 0; caso < 500; caso++) {
            Set<String> a = new HashSet<>(), b = new HashSet<>();
            for (String t : vocabulario) {
                if (rng.nextBoolean()) a.add(t);
                if (rng.nextBoolean()) b.add(t);
            }
            assertThat(jaccard.jaccardSimilarity(a, b))
                    .as("a=%s b=%s", a, b)
                    .isEqualTo(jaccardReferencia(a, b));
        }
    }

    @Test
    @DisplayName("dos sets vacíos siguen siendo idénticos y uno vacío sigue dando cero")
    void semanticaDeSetsVacios() {
        assertThat(jaccard.jaccardSimilarity(Set.of(), Set.of())).isEqualTo(1.0);
        assertThat(jaccard.jaccardSimilarity(Set.of("nike"), Set.of())).isEqualTo(0.0);
        assertThat(jaccard.jaccardSimilarity(Set.of(), Set.of("nike"))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("es simétrica: da lo mismo el orden de los argumentos")
    void esSimetrica() {
        Set<String> a = Set.of("nike", "air", "force");
        Set<String> b = Set.of("nike", "air", "max", "retro");
        assertThat(jaccard.jaccardSimilarity(a, b)).isEqualTo(jaccard.jaccardSimilarity(b, a));
    }
}
