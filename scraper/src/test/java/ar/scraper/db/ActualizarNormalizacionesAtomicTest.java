package ar.scraper.db;

import ar.scraper.catalog.Normalizacion;
import ar.scraper.catalog.ProductPort;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProductPort.actualizarNormalizaciones — all or nothing, rubro included")
class ActualizarNormalizacionesAtomicTest extends PostgresTestBase {

    private static final String A = "https://t/atomic-a";
    private static final String B = "https://t/atomic-b";
    private static final String C = "https://t/atomic-c";

    private DatabaseService db;
    private ProductPort productos;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
        productos = TestDatabaseServices.productos(dataSource());
        db.upsertProductos(List.of(producto(A), producto(B), producto(C)));
    }

    private Product producto(String url) {
        return Product.builder().sitio("freres").nombre("Producto").precio(15000.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("hombre")
                .talles(List.of("M")).ml(Product.MlScore.EMPTY).marca("Nike").rubro("indumentaria")
                .gymrat(false).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY).build();
    }

    private String one(String sql, String url) throws Exception {
        try (Connection c = dataSource().getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String estado(String url) throws Exception {
        return one("SELECT categoria || '|' || rubro || '|' || coalesce((SELECT string_agg(talle, ',' ORDER BY posicion) "
                + "FROM producto_talle t WHERE t.url = p.url), '') FROM productos p WHERE url = ?", url);
    }

    @Test
    @DisplayName("one row violating the categoria FK rolls back every row: category, rubro and sizes")
    void aViolatingRowRollsBackTheWholeBatch() throws Exception {
        List<Normalizacion> filas = List.of(
                new Normalizacion(A, "Pantalón", "Nike", "hombre", List.of("S", "L"), "", "hogar"),
                new Normalizacion(B, "Pantalón", "Nike", "hombre", null, "", "hogar"),
                new Normalizacion(C, "Categoria que no existe", "Nike", "hombre", List.of("XL"), "", "hogar"));

        assertThatThrownBy(() -> productos.actualizarNormalizaciones(filas)).isInstanceOf(RuntimeException.class);

        for (String url : List.of(A, B, C)) {
            assertThat(estado(url)).isEqualTo("Remera|indumentaria|M");
        }
    }

    @Test
    @DisplayName("rubro is persisted with the rest; null sizes stay, an empty list clears them, a blank rubro keeps the stored one")
    void rubroIsPersistedAndSizesFollowTheirContract() throws Exception {
        Set<String> actualizadas = productos.actualizarNormalizaciones(List.of(
                new Normalizacion(A, "Pantalón", "Nike", "hombre", null, "", "hogar"),
                new Normalizacion(B, "Pantalón", "Nike", "hombre", List.of(), "", ""),
                new Normalizacion(C, "Pantalón", "Nike", "hombre", List.of("S", "", "L"), "", "hogar")));

        assertThat(actualizadas).containsExactlyInAnyOrder(A, B, C);
        assertThat(estado(A)).isEqualTo("Pantalón|hogar|M");
        assertThat(estado(B)).isEqualTo("Pantalón|indumentaria|");
        assertThat(estado(C)).isEqualTo("Pantalón|hogar|S,L");
    }
}
