package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
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

@Epic("Persistence")
@Feature("Catalog query")
@Story("The rubro filter is served by idx_productos_rubro_precio")
@DisplayName("CatalogQueryRepository — the rubro predicate matches the V73 index expression")
class CatalogRubroIndexUsageTest extends PostgresTestBase {

    private static final String INDICE = "idx_productos_rubro_precio";

    private CatalogQueryRepository repositorio;

    @BeforeEach
    void sembrarCatalogo() throws Exception {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repositorio = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            String rubro = i % 3 == 0 ? "construccion" : i % 3 == 1 ? "hogar" : "indumentaria";
            productos.add(producto("https://t.example/" + i, rubro, 1000.0 + i));
        }
        assertThat(db.upsertProductos(productos).nuevos()).isEqualTo(120);
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("ANALYZE productos");
        }
    }

    private static Product producto(String url, String rubro, double precio) {
        return Product.builder().sitio("Easy").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria(categoriaDe(rubro)).genero("")
                .talles(List.of()).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .marca("").rubro(rubro).gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }

    private static String categoriaDe(String rubro) {
        return switch (rubro) {
            case "construccion" -> "Cemento";
            case "hogar" -> "Sanitario";
            default -> "Remera";
        };
    }

    private static CatalogFilter filtroDeRubro(String rubro) {
        return new CatalogFilter(null, null, null, null, null, null, null, null, rubro,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private record Consulta(String where, List<Object> params) {}

    @SuppressWarnings("unchecked")
    private Consulta whereDeProduccion(CatalogFilter filtro) throws Exception {
        Method construirWhere = CatalogQueryRepository.class.getDeclaredMethod("construirWhere", CatalogFilter.class);
        construirWhere.setAccessible(true);
        Object where = construirWhere.invoke(repositorio, filtro);
        Method sql = where.getClass().getDeclaredMethod("sql");
        Method params = where.getClass().getDeclaredMethod("params");
        sql.setAccessible(true);
        params.setAccessible(true);
        return new Consulta((String) sql.invoke(where), (List<Object>) params.invoke(where));
    }

    private String orderByDeProduccion(String orden) throws Exception {
        Method orderBy = CatalogQueryRepository.class.getDeclaredMethod("orderBy", String.class);
        orderBy.setAccessible(true);
        return (String) orderBy.invoke(repositorio, orden);
    }

    private static void assertEsCondicionDelIndice(String plan) {
        assertThat(plan).contains(INDICE).contains("Index Cond: (lower(").doesNotContain("Filter: (lower(");
    }

    private String plan(String sqlConWhere, List<Object> params) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
            }
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + sqlConWhere)) {
                int i = 1;
                for (Object p : params) ps.setObject(i++, p);
                StringBuilder plan = new StringBuilder();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                }
                return plan.toString();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the first page ordered by price is read through the V73 index")
    void pageQueryUsesTheIndex() throws Exception {
        Consulta consulta = whereDeProduccion(filtroDeRubro("construccion"));
        String sql = "SELECT p.url FROM productos p WHERE " + consulta.where() + "\n"
                + orderByDeProduccion("precio_asc") + "\nLIMIT 24 OFFSET 0";

        assertEsCondicionDelIndice(plan(sql, consulta.params()));
    }

    @Test
    @DisplayName("the count behind the page is also served by the V73 index")
    void countQueryUsesTheIndex() throws Exception {
        Consulta consulta = whereDeProduccion(filtroDeRubro("construccion"));
        String sql = "SELECT COUNT(*) FROM productos p WHERE " + consulta.where();

        assertEsCondicionDelIndice(plan(sql, consulta.params()));
    }

    @Test
    @DisplayName("the rubro filter is case-insensitive and still matches the index expression")
    void upperCaseRubroStillUsesTheIndex() throws Exception {
        Consulta consulta = whereDeProduccion(filtroDeRubro("HOGAR"));
        String sql = "SELECT p.url FROM productos p WHERE " + consulta.where() + "\n"
                + orderByDeProduccion("precio_asc") + "\nLIMIT 24 OFFSET 0";

        assertEsCondicionDelIndice(plan(sql, consulta.params()));
    }
}
