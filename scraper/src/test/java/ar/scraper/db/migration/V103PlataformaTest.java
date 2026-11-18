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

/**
 * Replays V103's own SQL over seeded rows: the platform sub and the recategorization of names whose leading noun
 * says they are not a GPU or a headset. {@code SubcategoryResolverTecnologiaTest} and
 * {@code TechLeadingNounBeforeGpuTest} pin the Java side with the same names (real rows, 2026-11-18).
 */
@DisplayName("V103 — plataforma del hardware y recategorización por sustantivo líder")
class V103PlataformaTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V103__tecnologia_subcategoria_plataforma.sql";

    @Test
    @DisplayName("The platform sub is written, glued sockets and the speed-to-DDR guess included")
    void plataforma() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(sub(st, "cpu-s1700")).isEqualTo("lga1700");
            assertThat(sub(st, "cpu-s1200")).isEqualTo("lga1200");
            assertThat(sub(st, "cpu-i5-8400")).isEqualTo("lga1151");
            assertThat(sub(st, "cpu-g6400")).isEqualTo("lga1200");
            assertThat(sub(st, "cpu-xeon")).isEmpty();
            assertThat(sub(st, "mb-b460")).isEqualTo("lga1200");
            assertThat(sub(st, "mb-h110")).isEqualTo("lga1151");
            assertThat(sub(st, "ram-ddr3")).isEqualTo("ddr3");
            assertThat(sub(st, "ram-dddr4")).isEqualTo("ddr4");
            assertThat(sub(st, "ram-6000mhz")).isEqualTo("ddr5");
            assertThat(sub(st, "ram-ddr2")).isEmpty();
            assertThat(sub(st, "gpu-r5")).isEqualTo("amd");
            assertThat(sub(st, "gpu-1030gt")).isEqualTo("nvidia");
            assertThat(sub(st, "aur-buds")).isEqualTo("in-ear");
            assertThat(sub(st, "aur-gamer")).isEqualTo("gamer");
            assertThat(sub(st, "aur-oficina")).isEqualTo("oficina");
            assertThat(sub(st, "aur-wireles")).isEqualTo("inalámbrico");
        });
    }

    @Test
    @DisplayName("Rows the leading noun refutes move with the sub of their new categoria; real cards and locked rows stay")
    void recategoriza() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(categoria(st, "wc-titan")).isEqualTo("Cooler");
            assertThat(sub(st, "wc-titan")).isEqualTo("líquida");
            assertThat(categoria(st, "pasta")).isEqualTo("Cooler");
            assertThat(sub(st, "pasta")).isEqualTo("pasta");
            assertThat(categoria(st, "gabinete-gpu")).isEqualTo("Gabinete");
            assertThat(categoria(st, "fuente-gpu")).isEqualTo("Fuente");
            assertThat(categoria(st, "mouse-rx")).isEqualTo("Mouse");
            assertThat(categoria(st, "madoka")).isEqualTo("Otros");
            assertThat(categoria(st, "soporte-gpu")).isEqualTo("Otros");
            assertThat(categoria(st, "my-arcade")).isEqualTo("Consola");
            assertThat(categoria(st, "mouse-bt")).isEqualTo("Mouse");
            assertThat(sub(st, "mouse-bt")).isEqualTo("inalámbrico");
            assertThat(categoria(st, "combo-teclado")).isEqualTo("Teclado");
            assertThat(categoria(st, "combo-ram-ssd")).isEqualTo("Combo de Actualización");
            assertThat(sub(st, "combo-ram-ssd")).isEmpty();
            assertThat(categoria(st, "gpu-real")).isEqualTo("GPU");
            assertThat(categoria(st, "aur-real")).isEqualTo("Auricular");
            assertThat(categoria(st, "bloqueado")).isEqualTo("GPU");
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
    @DisplayName("The documented rollback puts the moved rows back in their categoria and empties the platform subs")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            st.execute(DocumentedRollback.sqlFor("V103"));

            assertThat(categoria(st, "wc-titan")).isEqualTo("GPU");
            assertThat(categoria(st, "pasta")).isEqualTo("GPU");
            assertThat(categoria(st, "gabinete-gpu")).isEqualTo("GPU");
            assertThat(categoria(st, "mouse-rx")).isEqualTo("GPU");
            assertThat(categoria(st, "madoka")).isEqualTo("GPU");
            assertThat(categoria(st, "soporte-gpu")).isEqualTo("GPU");
            assertThat(categoria(st, "my-arcade")).isEqualTo("Auricular");
            assertThat(categoria(st, "mouse-bt")).isEqualTo("Auricular");
            assertThat(categoria(st, "combo-teclado")).isEqualTo("Auricular");
            assertThat(categoria(st, "combo-ram-ssd")).isEqualTo("RAM");
            assertThat(categoria(st, "gpu-real")).isEqualTo("GPU");
            assertThat(sub(st, "gpu-real")).isEmpty();
            assertThat(sub(st, "cpu-s1700")).isEmpty();
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
                        + "VALUES ('V103 Test', 'v103test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                    ('cpu-s1700',    'V103 Test', 'MICRO INTEL PENTIUM G7400 S1700',                                     1, 'CPU',         'tecnologia', true, NULL, NULL),
                    ('cpu-s1200',    'V103 Test', 'Procesador Intel Celeron G5905 c/Video c/Cooler S.1200',              1, 'CPU',         'tecnologia', true, NULL, NULL),
                    ('cpu-i5-8400',  'V103 Test', 'Microprocesador Intel Core i5 8400 Coffee Lake 4.0Ghz 9Mb',           1, 'CPU',         'tecnologia', true, NULL, NULL),
                    ('cpu-g6400',    'V103 Test', 'Procesador Intel Pentium Gold G6400',                                 1, 'CPU',         'tecnologia', true, NULL, NULL),
                    ('cpu-xeon',     'V103 Test', 'Procesador Intel Xeon  E5-2699 V3 Oem',                               1, 'CPU',         'tecnologia', true, NULL, NULL),
                    ('mb-b460',      'V103 Test', 'Motherboard ASUS Prime B460M-A',                                      1, 'Motherboard', 'tecnologia', true, NULL, NULL),
                    ('mb-h110',      'V103 Test', 'Motherboard ASUS Prime H110M-K',                                      1, 'Motherboard', 'tecnologia', true, NULL, NULL),
                    ('ram-ddr3',     'V103 Test', 'Memoria RAM Kingdian DDR3 4GB 1600MHz',                               1, 'RAM',         'tecnologia', true, NULL, NULL),
                    ('ram-dddr4',    'V103 Test', 'Memoria Ram Corsair 8gb Dddr4 3200mhz Vengeance Lpx Black',           1, 'RAM',         'tecnologia', true, NULL, NULL),
                    ('ram-6000mhz',  'V103 Test', 'Memoria RAM Corsair Vengeance 32GB(2x16GB) 6000MHz CL38',             1, 'RAM',         'tecnologia', true, NULL, NULL),
                    ('ram-ddr2',     'V103 Test', 'Memoria RAM Kimota DDR2 2GB 800MHz',                                  1, 'RAM',         'tecnologia', true, NULL, NULL),
                    ('gpu-r5',       'V103 Test', 'Placa de Video Arktek R5 230 Cyclops 2GB GDDR3',                      1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('gpu-1030gt',   'V103 Test', 'Placa de Video Peladn Nvidia 1030GT 4Gb Ddr4',                        1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('gpu-real',     'V103 Test', 'Placa de Video Sapphire Pulse RX 6600 8GB',                           1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('aur-buds',     'V103 Test', 'Auriculares Skullcandy Smokin Buds Matcha',                           1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('aur-gamer',    'V103 Test', 'Auriculares Corsair HS65 Surround White',                             1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('aur-oficina',  'V103 Test', 'Auriculares Logitech H390 con mic',                                   1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('aur-wireles',  'V103 Test', 'AURICULAR CORSAIR VIRTUOSO MAX WIRELES SILVER',                       1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('aur-real',     'V103 Test', 'Auricular Redragon Zeus X H510 RGB',                                  1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('wc-titan',     'V103 Test', 'Water Cooler Corsair ICUE LINK TITAN 240 RX RGB 2X 240MM Black',      1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('pasta',        'V103 Test', 'Pasta Térmica Corsair Tm30 De Alto Rendimiento Para Cpu / Gpu',       1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('gabinete-gpu', 'V103 Test', 'Gabinete HYTE Y40 Snow White Vertical GPU Mount BULK - OUTLET',       1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('fuente-gpu',   'V103 Test', 'Fuente 750W Corsair RM750e 80 PLUS Gold Full Modular Para GPU',       1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('mouse-rx',     'V103 Test', 'Mouse Cougar Surpassion RX Wireless 2.4Ghz Black RGB PMW 3330',       1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('madoka',       'V103 Test', 'MADOKA MAGICA WRAITH ARC (PACK)',                                     1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('soporte-gpu',  'V103 Test', 'Soporte para GPU Cooler Master Atlas ARGB VGA Bracket',               1, 'GPU',         'tecnologia', true, NULL, NULL),
                    ('my-arcade',    'V103 Test', 'My Arcade Micro Player Mini Arcade Machine: Galaga, Headphone Jack',   1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('mouse-bt',     'V103 Test', 'Mouse Logitech M196 Inalambrico BT Grafite',                          1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('combo-teclado','V103 Test', 'Combo Shinden Teclado Rkm75 He + Mouse M3 + Headset Hg103',           1, 'Auricular',   'tecnologia', true, NULL, NULL),
                    ('combo-ram-ssd','V103 Test', 'Combo Actualización Kingston Ram Fury Beast 16GB 3200 Mhz + SSD 1TB', 1, 'RAM',         'tecnologia', true, NULL, NULL),
                    ('bloqueado',    'V103 Test', 'Water Cooler Corsair Titan 360 RX LCD',                               1, 'GPU',         'tecnologia', true, 'local', now())
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V103PlataformaTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String categoria(Statement st, String url) throws Exception {
        return valor(st, "categoria", url);
    }

    private static String sub(Statement st, String url) throws Exception {
        return valor(st, "sub_categoria", url);
    }

    private static String valor(Statement st, String columna, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT " + columna + " FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1);
        }
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, categoria, sub_categoria FROM productos ORDER BY url")) {
            while (rs.next()) {
                sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append('/').append(rs.getString(3)).append(';');
            }
        }
        return sb.toString();
    }
}
