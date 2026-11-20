package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SiteRegistry.Sitio;
import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V118 — chair sub-categories and rubro: the SQL backfill agrees with the Java resolvers, and the rollback restores it")
class V118SillaSubcategoriaTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V118__silla_subcategoria_y_rubro.sql";

    private static final List<String> NOMBRES = List.of(
            "Silla Gamer Evolabs Graphen One", "Silla de escritorio Mint neumática giratoria tela Negra",
            "Silla de Comer Infanti Appetito Estrellas", "Silla de comedor Smile con brazos pack x 2 U",
            "Silla de Jardin Plegable De Calanda Midtown", "Silla Aimaretti Dakar", "Sillas de comedor/jardin Allegra gris",
            "Sillon Director Broksol Ejecutivo Premium Plegable", "Silla gamer de escritorio giratoria",
            "Set de 4 Banquetas Plegables Portátiles para Camping", "Silla Mecedora Eames Decototale",
            "Silla Plegable Tipo Booster Con Asiento De Plastico", "SILLA GAMER RAPTOR THRONE R20");

    @Test
    @DisplayName("on every site kind the stored sub and rubro equal the Java resolvers, and a locked row is left alone")
    void sqlAgreesWithJava() throws Exception {
        Map<String, Sitio> sitios = Map.of(
                "v118libre", new Sitio("V118libre", "v118libre", "vtex", false, null, "config"),
                "v118tec", new Sitio("V118tec", "v118tec", "osc", false, "tecnologia", "config"),
                "v118hogar", new Sitio("V118hogar", "v118hogar", "tiendanube", false, "hogar", "config"));
        RubroResolver rubros = new RubroResolver(SiteRegistry.forTesting(sitios));
        SubcategoryResolver subs = new SubcategoryResolver();
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES "
                        + "('V118libre','v118libre','vtex',false,NULL,'config'),"
                        + "('V118tec','v118tec','vtex',false,'tecnologia','config'),"
                        + "('V118hogar','v118hogar','vtex',false,'hogar','config')");
                int i = 0;
                Map<String, String> rubroGuardado = Map.of("V118libre", "oficina", "V118tec", "tecnologia", "V118hogar", "hogar");
                for (String sitio : rubroGuardado.keySet()) {
                    for (String nombre : NOMBRES) {
                        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) VALUES ('https://v118.test/"
                                + (i++) + "', '" + sitio + "', '" + nombre.replace("'", "''") + "', 1000, 'Silla', '" + rubroGuardado.get(sitio) + "')");
                    }
                }
                st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, bloqueado_por, bloqueado_at) VALUES "
                        + "('https://v118.test/locked', 'V118libre', 'Silla de comedor bloqueada', 1000, 'Silla', 'oficina', 'agent', now())");

                st.execute(migracion());

                int comparadas = 0;
                try (ResultSet rs = st.executeQuery("SELECT sitio_key, nombre, sub_categoria, rubro FROM productos "
                        + "WHERE url LIKE 'https://v118.test/%' AND url <> 'https://v118.test/locked'")) {
                    while (rs.next()) {
                        String sub = subs.resolver(rs.getString("nombre"), "Silla");
                        assertThat(rs.getString("sub_categoria")).as(rs.getString("nombre")).isEqualTo(sub);
                        assertThat(rs.getString("rubro")).as("%s / %s", rs.getString("sitio_key"), rs.getString("nombre"))
                                .isEqualTo(forced(rubros, rs.getString("sitio_key"), sub));
                        comparadas++;
                    }
                }
                assertThat(comparadas).isEqualTo(NOMBRES.size() * 3);
                try (ResultSet rs = st.executeQuery("SELECT sub_categoria, rubro FROM productos WHERE url = 'https://v118.test/locked'")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEmpty();
                    assertThat(rs.getString(2)).isEqualTo("oficina");
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static String forced(RubroResolver rubros, String sitioKey, String sub) {
        return rubros.resolver(sitioKey, "Silla", "oficina", sub);
    }

    @Test
    @DisplayName("the documented rollback empties the sub and puts a free-site chair back in oficina, a forced site untouched")
    void rollbackDocumentado() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES "
                        + "('V118libre','v118libre','vtex',false,NULL,'config'),('V118tec','v118tec','vtex',false,'tecnologia','config')");
                st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, sub_categoria) VALUES "
                        + "('https://v118.test/a', 'V118libre', 'Silla de comedor', 1000, 'Silla', 'hogar', 'comedor'),"
                        + "('https://v118.test/b', 'V118tec', 'Silla Gamer', 1000, 'Silla', 'tecnologia', 'gamer')");
                st.execute(DocumentedRollback.sqlFor("V118"));
                try (ResultSet rs = st.executeQuery("SELECT url, sub_categoria, rubro FROM productos WHERE url LIKE 'https://v118.test/%' ORDER BY url")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(2)).isEmpty();
                    assertThat(rs.getString(3)).isEqualTo("oficina");
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(2)).isEmpty();
                    assertThat(rs.getString(3)).isEqualTo("tecnologia");
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V118SillaSubcategoriaTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
