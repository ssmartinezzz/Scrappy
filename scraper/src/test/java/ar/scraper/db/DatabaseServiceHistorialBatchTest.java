package ar.scraper.db;

import ar.scraper.db.DatabaseService.HistorialEntry;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatabaseService#getHistorialPrecios(List)} — the batch price-history
 * load behind {@code SenalEnricher}, against a real Postgres.
 *
 * <p>It had unit coverage only through mocks, which cannot see the query
 * shape. That matters here: the original built {@code WHERE url IN (?,?,…)}
 * with one bind parameter per URL, which has two consequences a mock hides
 * entirely — the SQL text changes with every distinct batch size (so Postgres
 * never reuses a prepared plan), and the whole call collapses once the batch
 * crosses the wire protocol's 65535-parameter ceiling. The exception was
 * swallowed by the surrounding catch, so it degraded to "no product has any
 * price history" without a single failure surfacing.</p>
 *
 * <p>{@link #lotesMasGrandesQueElLimiteDeParametrosSiguenFuncionando()} is the
 * one that pins the fix: it fails on the parameter-per-URL shape and passes on
 * {@code = ANY(?)}, which binds a single array regardless of batch size.</p>
 */
@Epic("Persistence")
@Feature("Batch price-history load")
@Story("getHistorialPrecios(List)")
@DisplayName("DatabaseService — batch historial load on Postgres")
class DatabaseServiceHistorialBatchTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, double precio) {
        return new Product(
                "Sitio", "Producto", precio, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    // ─── Contract ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("carga el historial de varias URLs en una sola llamada")
    void cargaElHistorialDeVariasUrls() {
        db.upsertProductos(List.of(producto("https://site.com/a", 100.0),
                                   producto("https://site.com/b", 200.0)));

        Map<String, List<HistorialEntry>> historial =
                db.getHistorialPrecios(List.of("https://site.com/a", "https://site.com/b"));

        assertThat(historial).containsOnlyKeys("https://site.com/a", "https://site.com/b");
        assertThat(historial.get("https://site.com/a")).hasSize(1);
        assertThat(historial.get("https://site.com/a").get(0).precio()).isEqualTo(100.0);
        assertThat(historial.get("https://site.com/b").get(0).precio()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("una URL sin historial no aparece como clave del mapa")
    void urlSinHistorialNoApareceComoClave() {
        db.upsertProductos(List.of(producto("https://site.com/a", 100.0)));

        Map<String, List<HistorialEntry>> historial =
                db.getHistorialPrecios(List.of("https://site.com/a", "https://site.com/fantasma"));

        assertThat(historial).containsOnlyKeys("https://site.com/a");
    }

    @Test
    @DisplayName("el historial de cada URL viene ordenado por fecha ascendente")
    void elHistorialVieneOrdenadoPorFecha() {
        // Two runs at different prices on different days -> two history rows.
        db.upsertProductos(List.of(producto("https://site.com/a", 100.0)));
        insertarHistorial("https://site.com/a", "2026-01-05", 300.0);
        insertarHistorial("https://site.com/a", "2026-01-02", 200.0);

        List<HistorialEntry> entradas = db.getHistorialPrecios(List.of("https://site.com/a"))
                .get("https://site.com/a");

        assertThat(entradas).extracting(HistorialEntry::fecha).isSorted();
        assertThat(entradas).extracting(HistorialEntry::precio)
                .containsSubsequence(200.0, 300.0);
    }

    @Test
    @DisplayName("URLs nulas o en blanco se ignoran sin romper la consulta")
    void urlsNulasOEnBlancoSeIgnoran() {
        db.upsertProductos(List.of(producto("https://site.com/a", 100.0)));

        Map<String, List<HistorialEntry>> historial = db.getHistorialPrecios(
                Arrays.asList("https://site.com/a", null, "", "   "));

        assertThat(historial).containsOnlyKeys("https://site.com/a");
    }

    @Test
    @DisplayName("una URL repetida no duplica sus entradas")
    void urlRepetidaNoDuplicaEntradas() {
        db.upsertProductos(List.of(producto("https://site.com/a", 100.0)));

        Map<String, List<HistorialEntry>> historial = db.getHistorialPrecios(
                List.of("https://site.com/a", "https://site.com/a", "https://site.com/a"));

        assertThat(historial.get("https://site.com/a")).hasSize(1);
    }

    @Test
    @DisplayName("lista vacía devuelve mapa vacío sin consultar")
    void listaVaciaDevuelveMapaVacio() {
        assertThat(db.getHistorialPrecios(List.of())).isEmpty();
        assertThat(db.getHistorialPrecios((List<String>) null)).isEmpty();
    }

    // ─── The ceiling ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("un lote mayor al límite de 65535 parámetros sigue devolviendo el historial")
    void lotesMasGrandesQueElLimiteDeParametrosSiguenFuncionando() {
        db.upsertProductos(List.of(producto("https://site.com/real", 100.0)));

        // One real URL plus enough padding to blow past the wire protocol's
        // int16 parameter count. With one placeholder per URL the driver
        // refuses the statement outright and the swallowed exception turns
        // into "this catalog has no price history at all".
        List<String> urls = new ArrayList<>();
        urls.add("https://site.com/real");
        for (int i = 0; i < 70_000; i++) urls.add("https://site.com/relleno/" + i);

        Map<String, List<HistorialEntry>> historial = db.getHistorialPrecios(urls);

        assertThat(historial)
                .as("a 70k-URL batch must still resolve the one URL that has history")
                .containsOnlyKeys("https://site.com/real");
        assertThat(historial.get("https://site.com/real").get(0).precio()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("lotes de distinto tamaño devuelven resultados consistentes")
    void lotesDeDistintoTamanoSonConsistentes() {
        db.upsertProductos(List.of(producto("https://site.com/a", 100.0)));

        // Same answer whether the batch has 1, 10 or 1000 URLs — the query
        // shape must not be a function of the batch size.
        for (int tamano : new int[]{1, 10, 1000}) {
            List<String> urls = new ArrayList<>(List.of("https://site.com/a"));
            for (int i = 1; i < tamano; i++) urls.add("https://site.com/x" + i);

            assertThat(db.getHistorialPrecios(urls).get("https://site.com/a"))
                    .as("batch de %d URLs", tamano)
                    .hasSize(1);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Inserts a history row directly — upsert only records one row per day. */
    private void insertarHistorial(String url, String fecha, double precio) {
        try (var c = dataSource().getConnection();
             var ps = c.prepareStatement(
                     "INSERT INTO precio_historico(url, fecha, precio) VALUES (?,?,?) " +
                     "ON CONFLICT (url, fecha) DO NOTHING")) {
            ps.setString(1, url);
            ps.setString(2, fecha);
            ps.setDouble(3, precio);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("no pude sembrar historial", e);
        }
    }
}
