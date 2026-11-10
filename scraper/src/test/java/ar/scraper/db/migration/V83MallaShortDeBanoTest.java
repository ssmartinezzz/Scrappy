package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Replays V83's own SQL over seeded rows. Names are real rows from the dev database (2026-11-10). */
@DisplayName("V83 — swim shorts and trunks stored as Short/Calzoncillos become Malla")
class V83MallaShortDeBanoTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V83__malla_short_de_bano.sql";

    @Test
    @DisplayName("Swim shorts and boxer swimsuits move; plain shorts, Easy mesh and locked rows do not")
    void realinea() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(categoria(st, "swim-short")).isEqualTo("Malla");
            assertThat(categoria(st, "swim-inactivo")).isEqualTo("Malla");
            assertThat(categoria(st, "traje-boxer")).isEqualTo("Malla");
            assertThat(categoria(st, "bermuda-bano")).isEqualTo("Malla");
            assertThat(categoria(st, "short-comun")).isEqualTo("Short");
            assertThat(categoria(st, "boxer-comun")).isEqualTo("Calzoncillos");
            assertThat(categoria(st, "malla-easy")).isEqualTo("Otros");
            assertThat(categoria(st, "bloqueado")).isEqualTo("Short");
        });
    }

    @Test
    @DisplayName("A second run touches no row")
    void esIdempotente() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);
            String antes = snapshot(st);

            aplicar(st);

            assertThat(snapshot(st)).isEqualTo(antes);
        });
    }

    @Test
    @DisplayName("SQL and CategoryClassifier agree on the swim phrases")
    void paridadConElClasificador() throws Exception {
        CategoryClassifier clasificador = new CategoryClassifier();
        List<String> nombres = List.of("Swim Short Walker Black", "Swim Trunk Hombre", "Short de Baño Hombre Estampado",
                "Shorts de Baño Mujer", "Boxer de Baño Hombre Speedo", "Boxers de Baño Hombre", "Bermuda de Baño Palmeras",
                "Bermudas de Baño Hombre", "Trajes de Baño adidas BÓXERS SOLID SWIM de Hombre", "Short Deportivo Running",
                "Bermuda Cargo Hombre", "Boxer Algodón Hombre Negro");
        enTransaccion(st -> {
            int i = 0;
            for (String n : nombres) {
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('p" + i++
                        + "', 'V83 Test', '" + n + "', 1, '" + categoriaPrevia(n) + "', 'indumentaria', true)");
            }
            aplicar(st);

            try (ResultSet rs = st.executeQuery("SELECT nombre, categoria FROM productos ORDER BY url")) {
                while (rs.next()) {
                    boolean malla = "Malla".equals(clasificador.normalizarCategoria("", rs.getString(1)));
                    assertThat("Malla".equals(rs.getString(2))).as(rs.getString(1)).isEqualTo(malla);
                }
            }
        });
    }

    @Test
    @DisplayName("The documented rollback block runs")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> st.execute(DocumentedRollback.sqlFor("V83")));
    }

    /** What the classifier produced before this change, so the migration has something to realign. */
    private static String categoriaPrevia(String nombre) {
        String n = nombre.toLowerCase();
        if (n.startsWith("bermuda")) return "Bermuda";
        return n.contains("boxer") && !n.contains("trajes") && !n.contains("de bano") && !n.contains("de baño")
                ? "Calzoncillos" : "Short";
    }

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET CONSTRAINTS ALL IMMEDIATE");
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V83 Test', 'v83test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                    ('swim-short',    'V83 Test', 'Swim Short Walker Black',                            1, 'Short',        'indumentaria', true,  NULL,    NULL),
                    ('swim-inactivo', 'V83 Test', 'Swim Short Peaches Navy',                            1, 'Short',        'indumentaria', false, NULL,    NULL),
                    ('traje-boxer',   'V83 Test', 'Trajes de Baño adidas BÓXERS SOLID SWIM de Hombre',  1, 'Calzoncillos', 'indumentaria', true,  NULL,    NULL),
                    ('bermuda-bano',  'V83 Test', 'Bermuda de Baño Hombre Palmeras',                    1, 'Bermuda',      'indumentaria', true,  NULL,    NULL),
                    ('short-comun',   'V83 Test', 'Short Deportivo Hombre Running',                     1, 'Short',        'indumentaria', true,  NULL,    NULL),
                    ('boxer-comun',   'V83 Test', 'Boxer Algodón Hombre Negro',                         1, 'Calzoncillos', 'indumentaria', true,  NULL,    NULL),
                    ('malla-easy',    'V83 Test', 'Malla Cuadrada Hierro 100x200 Cm 2 Mm Serin',        1, 'Otros',        'construccion', true,  NULL,    NULL),
                    ('bloqueado',     'V83 Test', 'Swim Short Bloqueado',                               1, 'Short',        'indumentaria', true,  'local', now())
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V83MallaShortDeBanoTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String categoria(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT categoria FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1);
        }
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, categoria FROM productos ORDER BY url")) {
            while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append(';');
        }
        return sb.toString();
    }
}
