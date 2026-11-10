package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.GenderResolver;
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

/** Replays V81's own SQL over seeded rows. Names are real rows from the dev database (2026-11-10). */
@DisplayName("V81 — kids name signals backfill genero = 'infantil'")
class V81InfantilSenalesDeNombreTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V81__infantil_senales_de_nombre.sql";

    @Test
    @DisplayName("Tags the kids signals, leaves adult collabs, non-clothing words and locked rows alone")
    void backfill() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(genero(st, "footy")).isEqualTo("infantil");
            assertThat(genero(st, "footy-hogar")).isEqualTo("infantil");
            assertThat(genero(st, "juvenil")).isEqualTo("infantil");
            assertThat(genero(st, "minnie")).isEqualTo("infantil");
            assertThat(genero(st, "frozen-inactiva")).isEqualTo("infantil");
            assertThat(genero(st, "peppa-null")).isEqualTo("infantil");
            assertThat(genero(st, "marvel")).isEqualTo("hombre");
            assertThat(genero(st, "disney-adulto")).isEqualTo("");
            assertThat(genero(st, "stitch")).isEqualTo("unisex");
            assertThat(genero(st, "peppa-hogar")).isEqualTo("");
            assertThat(genero(st, "escolar-oficina")).isEqualTo("");
            assertThat(genero(st, "bloqueada")).isEqualTo("hombre");
        });
    }

    @Test
    @DisplayName("A second run changes nothing")
    void idempotent() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);
            String antes = snapshot(st);

            aplicar(st);

            assertThat(snapshot(st)).isEqualTo(antes);
        });
    }

    @Test
    @DisplayName("SQL and GenderResolver agree on every signal, in clothing and out of it")
    void parityWithJava() throws Exception {
        GenderResolver resolver = new GenderResolver();
        List<String> nombres = List.of("Zapatillas Footy Spiderman", "Remera Footyball Club", "Medias Ushuaia Juvenil",
                "Medias Ushuaia JUVENILES", "Zapatillas Footy Escolar", "Libreta Escolar A4", "Sandalias Crocs Disney Minnie Mouse",
                "Mochila LSyD Disney Mickey 16\"", "Sandalias Crocs Paw Patrol Off Court", "Sandalias Crocs Peppa Pig",
                "Ojotas Crocs Frozen Elsa Classic Clog", "Zapatillas Toy Story", "Buzo Minnesota Wild",
                "Ojotas adidas Marvel Spider-Man Adilette Unisex", "Bota Dr Martens Stitch Smooth");
        enTransaccion(st -> {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('V81 Test', 'v81test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
            for (String rubro : List.of("indumentaria", "tecnologia")) {
                int i = 0;
                for (String n : nombres) {
                    st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro, genero, activo) VALUES ('https://p/"
                            + rubro + "/" + String.format("%03d", i++) + "', 'V81 Test', '" + n.replace("'", "''")
                            + "', 1000, '" + rubro + "', '', true)");
                }
            }
            aplicar(st);

            try (ResultSet rs = st.executeQuery("SELECT nombre, rubro, genero FROM productos ORDER BY url")) {
                int rows = 0;
                while (rs.next()) {
                    String esperado = resolver.resolver("", rs.getString(1), "Otros", rs.getString(2)).equals("infantil")
                            ? "infantil" : "";
                    assertThat(rs.getString(3)).as(rs.getString(2) + " / " + rs.getString(1)).isEqualTo(esperado);
                    rows++;
                }
                assertThat(rows).isEqualTo(nombres.size() * 2);
            }
        });
    }

    @Test
    @DisplayName("The documented rollback block runs")
    void rollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(DocumentedRollback.sqlFor("V81"));
        }
    }

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('V81 Test', 'v81test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, genero, activo, bloqueado_por, bloqueado_at) VALUES
                    ('footy',           'V81 Test', 'Zapatillas Footy Spiderman',                      1000, 'Zapatilla', 'indumentaria', '',       true,  NULL,    NULL),
                    ('footy-hogar',     'V81 Test', 'Tupper Plegable Footy Unicornio',                 1000, 'Otros',     'hogar',         'unisex', true,  NULL,    NULL),
                    ('juvenil',         'V81 Test', 'Medias Ushuaia Juvenil',                          1000, 'Medias',    'indumentaria', 'unisex', true,  NULL,    NULL),
                    ('minnie',          'V81 Test', 'Mochila LSyD Disney Minnie 16"',                  1000, 'Mochila',   NULL,            '',       true,  NULL,    NULL),
                    ('frozen-inactiva', 'V81 Test', 'Ojotas Crocs Frozen Elsa Classic Clog',           1000, 'Ojotas',    'indumentaria', 'mujer',  false, NULL,    NULL),
                    ('peppa-null',      'V81 Test', 'Sandalias Crocs Peppa Pig',                       1000, 'Ojotas',    'indumentaria', NULL,     true,  NULL,    NULL),
                    ('marvel',          'V81 Test', 'Ojotas adidas de Ducha Marvel Spider-Man Adilette Unisex', 1000, 'Ojotas', 'indumentaria', 'hombre', true, NULL, NULL),
                    ('disney-adulto',   'V81 Test', 'Zapatillas adidas originals Disney Campus 00 S',  1000, 'Zapatilla', 'indumentaria', '',       true,  NULL,    NULL),
                    ('stitch',          'V81 Test', 'Bota Dr Martens Chelsea 2976 Yellow Stitch Smooth', 1000, 'Borcego', 'indumentaria', 'unisex', true,  NULL,    NULL),
                    ('peppa-hogar',     'V81 Test', 'Peppa Pig Juguete Para Mascotas',                 1000, 'Otros',     'hogar',         '',       true,  NULL,    NULL),
                    ('escolar-oficina', 'V81 Test', 'Libreta Escolar A4 Rayada',                       1000, 'Otros',     'oficina',       '',       true,  NULL,    NULL),
                    ('bloqueada',       'V81 Test', 'Remera Footy Bloqueada',                          1000, 'Remera',    'indumentaria', 'hombre', true,  'local', now())
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        st.execute("SET CONSTRAINTS ALL IMMEDIATE");
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V81InfantilSenalesDeNombreTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String genero(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT coalesce(genero, '') FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1);
        }
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, genero FROM productos ORDER BY url")) {
            while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append(';');
        }
        return sb.toString();
    }
}
