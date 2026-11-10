package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CatalogQueryRepository — a single-brand price page is an ordered scan of idx_productos_marca_precio (V89)")
class CatalogMarcaIndexUsageTest extends PostgresTestBase {

    private CatalogQueryRepository repositorio;

    @BeforeEach
    void sembrar() throws Exception {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repositorio = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < 200; i++) productos.add(producto("https://t.example/" + i, i % 2 == 0 ? "Nike" : "Adidas", 1000.0 + i));
        assertThat(db.upsertProductos(productos).nuevos()).isEqualTo(200);
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("ANALYZE productos");
        }
    }

    private static Product producto(String url, String marca, double precio) {
        return Product.builder().sitio("Test").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex").marca(marca)
                .talles(List.of()).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).build();
    }

    @Test
    @DisplayName("keyset query for one brand: index condition on the brand and the cursor, backward scan for precio_desc, no Sort")
    void unaMarcaSeLeePorElIndice() throws Exception {
        Method construirWhere = CatalogQueryRepository.class.getDeclaredMethod("construirWhere", CatalogFilter.class);
        construirWhere.setAccessible(true);
        Object where = construirWhere.invoke(repositorio, CatalogFilter.todo().conMarcas(List.of("Nike")));
        Method sqlM = where.getClass().getDeclaredMethod("sql");
        Method paramsM = where.getClass().getDeclaredMethod("params");
        sqlM.setAccessible(true);
        paramsM.setAccessible(true);
        String sql = (String) sqlM.invoke(where);
        @SuppressWarnings("unchecked") List<Object> params = (List<Object>) paramsM.invoke(where);

        String asc = plan("SELECT p.url FROM productos p WHERE " + sql
                + " AND (p.precio, p.url) > (1100, 'https://t.example/') ORDER BY p.precio ASC, p.url ASC LIMIT 25", params);
        String desc = plan("SELECT p.url FROM productos p WHERE " + sql
                + " AND (p.precio, p.url) < (1100, 'https://t.example/') ORDER BY p.precio DESC, p.url DESC LIMIT 25", params);

        assertThat(asc).contains("idx_productos_marca_precio").doesNotContain("Sort");
        assertThat(desc).contains("Index Scan Backward using idx_productos_marca_precio").doesNotContain("Sort");
    }

    private String plan(String sql, List<Object> params) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                st.execute("SET LOCAL enable_bitmapscan = off");
            }
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + sql)) {
                int i = 1;
                for (Object p : params) ps.setObject(i++, p);
                StringBuilder out = new StringBuilder();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.append(rs.getString(1)).append('\n');
                }
                return out.toString();
            } finally {
                c.rollback();
            }
        }
    }
}
