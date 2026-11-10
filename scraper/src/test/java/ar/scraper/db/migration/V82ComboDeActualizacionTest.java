package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Replays V82's own SQL over seeded rows. Names are real rows from the dev database (2026-11-10). */
@DisplayName("V82 — Combo de Actualización: the category row and the realignment of stored kits")
class V82ComboDeActualizacionTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V82__categoria_combo_de_actualizacion.sql";
    private static final String COMBO = "Combo de Actualización";

    @Test
    @DisplayName("The category exists, so the FK accepts what the classifier produces")
    void laCategoriaExiste() throws Exception {
        enTransaccion(st -> assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = '" + COMBO + "'"))
                .isEqualTo(1));
    }

    @Test
    @DisplayName("Kits filed as Motherboard, CPU or Cooler move; full PCs, lone parts and locked rows do not")
    void realinea() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(categoria(st, "kit-mother")).isEqualTo(COMBO);
            assertThat(categoria(st, "kit-cpu-primero")).isEqualTo(COMBO);
            assertThat(categoria(st, "combo-mb")).isEqualTo(COMBO);
            assertThat(categoria(st, "combo-glued")).isEqualTo(COMBO);
            assertThat(categoria(st, "combo-inactivo")).isEqualTo(COMBO);
            assertThat(categoria(st, "pc")).isEqualTo("PC");
            assertThat(categoria(st, "cpu-cooler")).isEqualTo("CPU");
            assertThat(categoria(st, "mother-sola")).isEqualTo("Motherboard");
            assertThat(categoria(st, "cpu-sola")).isEqualTo("CPU");
            assertThat(categoria(st, "notebook")).isEqualTo("Notebook");
            assertThat(categoria(st, "combo-cpu-cooler")).isEqualTo("Cooler");
            assertThat(categoria(st, "bloqueado")).isEqualTo("Motherboard");
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
    @DisplayName("The documented rollback runs and puts the kits back in Motherboard")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            st.execute(DocumentedRollback.sqlFor("V82"));

            assertThat(categoria(st, "kit-mother")).isEqualTo("Motherboard");
            assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = '" + COMBO + "'")).isZero();
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
                        + "VALUES ('V82 Test', 'v82test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                    ('kit-mother',       'V82 Test', 'Kit Mother ASUS PRIME A520M-K + Procesador AMD Ryzen 5 5500 4.2GHz Turbo AM4 + Wraith Stealth Cooler', 1, 'Motherboard', 'tecnologia', true,  NULL,    NULL),
                    ('kit-cpu-primero', 'V82 Test', 'Kit Procesador Intel Core I7 12700 4.9GHz Turbo (Con Video Integrado) + Mother ASUS PRIME H610M',        1, 'Motherboard', 'tecnologia', true,  NULL,    NULL),
                    ('combo-mb',        'V82 Test', 'Combo Actualización Amd Ryzen 5 3400g + Mb A520m + Cooler',                                           1, 'Cooler',      'tecnologia', true,  NULL,    NULL),
                    ('combo-glued',     'V82 Test', 'COMBO AMD RYZEN 7 5700X+B550+16GB 3200',                                                              1, 'CPU',         'tecnologia', true,  NULL,    NULL),
                    ('combo-inactivo',  'V82 Test', 'Combo Ryzen 5 8400f / A620m / Cooler',                                                                1, 'Cooler',      'tecnologia', false, NULL,    NULL),
                    ('pc',              'V82 Test', 'PC ARMADA AMD RYZEN 5 5600GT+A520+16GB+1TB M.2 NVME+GABINETE KIT',                                    1, 'PC',          'tecnologia', true,  NULL,    NULL),
                    ('cpu-cooler',      'V82 Test', 'Procesador AMD Ryzen 5 5500 4.2GHz Turbo AM4 + Wraith Stealth Cooler',                                1, 'CPU',         'tecnologia', true,  NULL,    NULL),
                    ('mother-sola',     'V82 Test', 'Mother ASUS PRIME A520M-K AM4 DDR4',                                                                  1, 'Motherboard', 'tecnologia', true,  NULL,    NULL),
                    ('cpu-sola',        'V82 Test', 'Procesador Intel Pentium G4560 (Chipset H110/B150/B250/H170/Z170) LGA1151 KABY LAKE OEM',             1, 'CPU',         'tecnologia', true,  NULL,    NULL),
                    ('notebook',        'V82 Test', 'Notebook Acer Aspire 3 A315 Ryzen 5 7520U 16GB 512GB Touch W11 + Mochila',                            1, 'Notebook',    'tecnologia', true,  NULL,    NULL),
                    ('combo-cpu-cooler','V82 Test', 'Combo Ryzen 5 8400f & Cooler Jiushark Jf100rs',                                                       1, 'Cooler',      'tecnologia', true,  NULL,    NULL),
                    ('bloqueado',       'V82 Test', 'Kit Mother ASUS PRIME B550M-K + Procesador AMD Ryzen 5 4500 + Wraith Stealth Cooler',                 1, 'Motherboard', 'tecnologia', true,  'local', now())
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V82ComboDeActualizacionTest.class.getResourceAsStream(MIGRATION)) {
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

    private static int cuantos(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
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
