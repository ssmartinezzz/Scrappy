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

/** Replays V88's own SQL over seeded rows (names from the dev catalog, 2026-11-10). */
@DisplayName("V88 — Top / Top Deportivo categories, realignment of tops, canilleras and tubeless sealant")
class V88TopYTopDeportivoTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V88__categoria_top_y_top_deportivo.sql";

    @Test
    @DisplayName("the migration seeds both categoria rows (the FK target)")
    void seedsTheCategoriaRows() throws Exception {
        enTransaccion(st -> {
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Top', 'Top Deportivo')"))
                    .isEqualTo("2");
            st.execute(DocumentedRollback.sqlFor("V88"));
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Top', 'Top Deportivo')"))
                    .isEqualTo("0");
            aplicar(st);
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Top', 'Top Deportivo')"))
                    .isEqualTo("2");
        });
    }

    @Test
    @DisplayName("tops split three ways; tanks, locked rows and other rubros stay")
    void realineaLosTops() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(categoria(st, "td-explicito")).isEqualTo("Top Deportivo");
            assertThat(categoria(st, "td-sports-bra")).as("was Corpino through ' bra '").isEqualTo("Top Deportivo");
            assertThat(categoria(st, "td-workout")).isEqualTo("Top Deportivo");
            assertThat(categoria(st, "td-marca")).isEqualTo("Top Deportivo");
            assertThat(categoria(st, "td-lider")).as("leading noun beats Tank").isEqualTo("Top Deportivo");
            assertThat(categoria(st, "top-vestir")).isEqualTo("Top");
            assertThat(categoria(st, "top-sin-rubro")).as("NULL rubro counts as indumentaria").isEqualTo("Top");
            assertThat(categoria(st, "croptop")).isEqualTo("Top");
            assertThat(categoria(st, "corpino-encaje")).isEqualTo("Corpino");
            assertThat(categoria(st, "musculosa")).isEqualTo("Musculosa");
            assertThat(categoria(st, "tank-top")).as("the top qualifies the tank").isEqualTo("Musculosa");
            assertThat(categoria(st, "top-cuello")).as("left for the next scrape, not a top").isEqualTo("Musculosa");
            assertThat(categoria(st, "bra-cruzado")).isEqualTo("Corpino");
            assertThat(categoria(st, "top-bloqueado")).as("locked by hand").isEqualTo("Musculosa");
            assertThat(categoria(st, "top-juguete")).as("other rubro").isEqualTo("Musculosa");
        });
    }

    @Test
    @DisplayName("gender: moved tops become mujer, except kids' rows, an explicit men signal and locked rows")
    void generoDeLosTops() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, genero, activo, bloqueado_por, bloqueado_at) VALUES
                        ('g-top-hombre-tag', 'V88 Test', 'Top Basico Negro',            1, 'Musculosa', 'indumentaria', 'hombre',   true, NULL,    NULL),
                        ('g-top-infantil',   'V88 Test', 'Top Nena Rosa',               1, 'Musculosa', 'indumentaria', 'infantil', true, NULL,    NULL),
                        ('g-top-de-hombre',  'V88 Test', 'Top Compresion de Hombre',    1, 'Musculosa', 'indumentaria', 'hombre',   true, NULL,    NULL),
                        ('g-top-bloqueado',  'V88 Test', 'Top Liso Bloqueado',          1, 'Musculosa', 'indumentaria', 'hombre',   true, 'local', now()),
                        ('g-musculosa',      'V88 Test', 'Musculosa Lisa',              1, 'Musculosa', 'indumentaria', 'hombre',   true, NULL,    NULL)
                    """);
            aplicar(st);

            assertThat(genero(st, "g-top-hombre-tag")).as("bare top, site tag loses").isEqualTo("mujer");
            assertThat(genero(st, "td-marca")).as("NULL genero").isEqualTo("mujer");
            assertThat(genero(st, "g-top-infantil")).isEqualTo("infantil");
            assertThat(genero(st, "g-top-de-hombre")).as("explicit men signal in the name").isEqualTo("hombre");
            assertThat(genero(st, "g-top-bloqueado")).isEqualTo("hombre");
            assertThat(genero(st, "g-musculosa")).as("not a top").isEqualTo("hombre");
        });
    }

    @Test
    @DisplayName("canilleras and the sealant injector move; construction sealant and locked rows do not")
    void realineaCanillerasYSellante() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(categoria(st, "canillera-otros")).isEqualTo("Accesorio Deportivo");
            assertThat(categoria(st, "canillera-medias")).isEqualTo("Accesorio Deportivo");
            assertThat(categoria(st, "canillera-botines")).isEqualTo("Accesorio Deportivo");
            assertThat(categoria(st, "medias-con-canillera")).as("not leading").isEqualTo("Medias");
            assertThat(categoria(st, "inyector")).isEqualTo("Repuesto de Bicicleta");
            assertThat(unaColumna(st, "SELECT rubro FROM productos WHERE url = 'inyector'")).isEqualTo("deporte");
            assertThat(categoria(st, "sellante-tubeless")).isEqualTo("Repuesto de Bicicleta");
            assertThat(categoria(st, "sellante-obra")).isEqualTo("Otros");
            assertThat(categoria(st, "canillera-bloqueada")).isEqualTo("Otros");
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
    @DisplayName("SQL and CategoryClassifier agree on every top")
    void paridadConElClasificador() throws Exception {
        CategoryClassifier clasificador = new CategoryClassifier();
        List<String> nombres = List.of(
                "Top adidas Optime Workout De Mujer", "Top Deportivo Mujer Vaypol", "Sports Bra Nike Dri-Fit",
                "Corpino Deportivo Alto Impacto", "Top Under Armour Halo Run Tank", "Top Mujer Training Sin Costuras",
                "Top Puma Mujer Negro", "Top Soporte Medio Yoga", "TOP COZY", "Top Margot Crudo",
                "Top Midway Satinado Mujer", "Top Encaje Negro", "Top Lenceria Puntilla Mujer", "Top Interior Algodón",
                "Top Cuello Alto Manga Corta", "Tank Top Mujer Gris", "Musculosa Nike Dri-Fit", "Camiseta de Tirantes Basica",
                "Crop Top Training Mujer", "Crop Top Satin Mujer", "CROPTOP 4REAL NEGRO");
        enTransaccion(st -> {
            int i = 0;
            for (String n : nombres) {
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('p" + i++
                        + "', 'V88 Test', '" + n.replace("'", "''") + "', 1, '" + (n.contains("CROPTOP") ? "Corpino" : "Musculosa") + "', 'indumentaria', true)");
            }
            aplicar(st);

            try (ResultSet rs = st.executeQuery("SELECT nombre, categoria FROM productos ORDER BY url")) {
                while (rs.next()) {
                    String esperada = clasificador.normalizarCategoria("", rs.getString(1));
                    String guardada = rs.getString(2);
                    // Rows the classifier sends outside the top family (Remera, tanks) are the next scrape's job.
                    if (List.of("Top", "Top Deportivo", "Corpino").contains(esperada)) {
                        assertThat(guardada).as(rs.getString(1)).isEqualTo(esperada);
                    } else {
                        assertThat(guardada).as(rs.getString(1)).isEqualTo("Musculosa");
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("The documented rollback sends Top and Top Deportivo rows back to Musculosa and removes the rows")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);
            assertThat(categoria(st, "top-vestir")).isEqualTo("Top");

            st.execute(DocumentedRollback.sqlFor("V88"));

            assertThat(categoria(st, "top-vestir")).isEqualTo("Musculosa");
            assertThat(categoria(st, "td-explicito")).isEqualTo("Musculosa");
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Top', 'Top Deportivo')"))
                    .isEqualTo("0");
        });
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
                        + "VALUES ('V88 Test', 'v88test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                    ('td-explicito',        'V88 Test', 'Top Deportivo Mujer Vaypol',          1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('td-sports-bra',       'V88 Test', 'Sports Bra Nike Dri-Fit',             1, 'Corpino',   'indumentaria', true, NULL,    NULL),
                    ('td-workout',          'V88 Test', 'Top Mujer Workout Sin Costuras',      1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('td-marca',            'V88 Test', 'Top Puma Mujer Negro',                1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('td-lider',            'V88 Test', 'Top Under Armour Halo Run Tank',      1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('top-vestir',          'V88 Test', 'TOP COZY',                            1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('top-sin-rubro',       'V88 Test', 'Top Margot Crudo',                    1, 'Musculosa', NULL,           true, NULL,    NULL),
                    ('croptop',             'V88 Test', 'CROPTOP 4REAL NEGRO',                 1, 'Corpino',   'indumentaria', true, NULL,    NULL),
                    ('corpino-encaje',      'V88 Test', 'Top Encaje Negro',                    1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('musculosa',           'V88 Test', 'Musculosa Nike Dri-Fit',              1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('tank-top',            'V88 Test', 'Tank Top Mujer Gris',                 1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('top-cuello',          'V88 Test', 'Top Cuello Alto Manga Corta',         1, 'Musculosa', 'indumentaria', true, NULL,    NULL),
                    ('bra-cruzado',         'V88 Test', 'Bras Cruzado',                        1, 'Corpino',   'indumentaria', true, NULL,    NULL),
                    ('top-bloqueado',       'V88 Test', 'Top Bloqueado',                       1, 'Musculosa', 'indumentaria', true, 'local', now()),
                    ('top-juguete',         'V88 Test', 'Muñeca Pinypon Top Girls',            1, 'Musculosa', 'juguetes',     true, NULL,    NULL),
                    ('canillera-otros',     'V88 Test', 'Canilleras Nike Mercurial Lite',      1, 'Otros',     'indumentaria', true, NULL,    NULL),
                    ('canillera-medias',    'V88 Test', 'Canilleras adidas Medias con Niños',  1, 'Medias',    'indumentaria', true, NULL,    NULL),
                    ('canillera-botines',   'V88 Test', 'Canillera Puma Ultra Flex',           1, 'Botines',   'indumentaria', true, NULL,    NULL),
                    ('medias-con-canillera','V88 Test', 'Medias Futbol con Canillera',         1, 'Medias',    'indumentaria', true, NULL,    NULL),
                    ('inyector',            'V88 Test', 'Inyector De Sellante Top Mega Tubeless', 1, 'Musculosa', 'indumentaria', true, NULL, NULL),
                    ('sellante-tubeless',   'V88 Test', 'Sellante Tubeless 250ml',             1, 'Otros',     'indumentaria', true, NULL,    NULL),
                    ('sellante-obra',       'V88 Test', 'Sellante Poliuretano Sikaflex 300ml', 1, 'Otros',     'indumentaria', true, NULL,    NULL),
                    ('canillera-bloqueada', 'V88 Test', 'Canilleras Bloqueadas',               1, 'Otros',     'indumentaria', true, 'local', now())
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V88TopYTopDeportivoTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String unaColumna(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static String categoria(Statement st, String url) throws Exception {
        return unaColumna(st, "SELECT categoria FROM productos WHERE url = '" + url + "'");
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, categoria, rubro FROM productos ORDER BY url")) {
            while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append('/')
                    .append(rs.getString(3)).append(';');
        }
        return sb.toString();
    }

    private static String genero(Statement st, String url) throws Exception {
        return unaColumna(st, "SELECT genero FROM productos WHERE url = '" + url + "'");
    }
}
