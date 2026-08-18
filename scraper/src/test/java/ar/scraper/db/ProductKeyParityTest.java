package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El handle corto de un producto se calcula en DOS lados: la columna generada
 * {@code productos.producto_key} (V25, la que tiene el índice y resuelve la
 * búsqueda) y {@code ProductKey.of} en Java (que lo manda en cada fila del
 * catálogo sin ir a la base).
 *
 * <p>Dos implementaciones de la misma regla es exactamente la forma que este
 * proyecto ya pagó una vez: tres parsers de precio que se desincronizaron y
 * terminaron acordando una respuesta 100x equivocada. La lección de ahí no fue
 * "no dupliques", fue "si duplicás, que un test las corra a las dos sobre los
 * mismos datos". Eso es este archivo.</p>
 *
 * <p>El corpus no es decorativo: cada URL apunta a una forma en la que un hash
 * de texto puede divergir entre lenguajes — encoding no-ASCII, espacios,
 * caracteres percent-encodeados, query strings, largo, y el caso vacío.</p>
 */
@Epic("Base de datos")
@Feature("producto_key")
@Story("La columna generada y el helper Java no pueden divergir")
@DisplayName("ProductKey.of (Java) == productos.producto_key (SQL)")
class ProductKeyParityTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    /** Cada entrada es una forma distinta de romper un hash de texto entre lenguajes. */
    private static final List<String> URLS = List.of(
            "https://site.com/remera-negra",
            "https://site.com/productos/notebook-15-6-i7-16gb",
            "https://site.com/buscar?q=remera&color=negro&page=2",
            "https://site.com/categoría/niños/muñeco",          // no-ASCII: UTF-8 de los dos lados
            "https://site.com/con espacio/en la ruta",           // espacios sin encodear
            "https://site.com/ya%20percent%20encodeado",         // el % no se re-interpreta
            "https://site.com/" + "x".repeat(300),               // largo: el hash no depende del largo
            "https://site.com/a'comilla\"doble",                 // comillas: no rompen el bind
            "https://site.com/emoji-👕-producto");

    private Product producto(String url, int i) {
        return new Product("Sitio", "Producto " + i, 1000 + i, null, url,
                "http://img.example/x.jpg", "Remera", "unisex", List.of(),
                Product.MlScore.EMPTY, "Nike", "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    @DisplayName("para cada URL, Postgres y Java calculan el MISMO handle")
    void javaAndPostgresAgreeOnEveryUrl() throws Exception {
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < URLS.size(); i++) productos.add(producto(URLS.get(i), i));

        DatabaseService.UpsertStats stats = db.upsertProductos(productos);
        // El upsert se traga los errores SQL y sale como UpsertStats(0,0,0,0):
        // sin esta guarda, todo lo de abajo compararía contra una tabla vacía.
        assertThat(stats.nuevos()).isEqualTo(URLS.size());

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT url, producto_key FROM productos ORDER BY url")) {
            try (ResultSet rs = ps.executeQuery()) {
                int vistas = 0;
                while (rs.next()) {
                    String url = rs.getString("url");
                    assertThat(rs.getString("producto_key"))
                            .as("handle de %s", url)
                            .isEqualTo(ar.scraper.web.ProductKeyTestBridge.of(url));
                    vistas++;
                }
                assertThat(vistas).isEqualTo(URLS.size());
            }
        }
    }

    @Test
    @DisplayName("el handle es estable: re-upsertar no lo cambia")
    void theHandleSurvivesAReUpsert() throws Exception {
        db.upsertProductos(List.of(producto(URLS.get(0), 0)));
        String antes = leerKey(URLS.get(0));

        // Mismo producto, precio distinto -> UPDATE, no INSERT.
        db.upsertProductos(List.of(producto(URLS.get(0), 999)));

        assertThat(leerKey(URLS.get(0))).isEqualTo(antes);
    }

    @Test
    @DisplayName("dos URLs distintas no comparten handle")
    void differentUrlsGetDifferentHandles() throws Exception {
        db.upsertProductos(List.of(producto(URLS.get(0), 0), producto(URLS.get(1), 1)));

        assertThat(leerKey(URLS.get(0))).isNotEqualTo(leerKey(URLS.get(1)));
    }

    private String leerKey(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT producto_key FROM productos WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("producto %s presente", url).isTrue();
                return rs.getString(1);
            }
        }
    }
}
