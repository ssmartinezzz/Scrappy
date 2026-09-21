package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TechSpecsPort;
import ar.scraper.pcs.TipoAlmacenamiento;
import ar.scraper.pcs.TipoCooler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V35 (T5) — {@code producto_tech_specs}: one row per url, upserted. */
class TechSpecsRepositoryTest extends PostgresTestBase {

    private TechSpecsPort repository;

    @BeforeEach
    void setUp() {
        repository = new TechSpecsRepository(dataSource());
    }

    @Test
    void emptyBatchIsANoOp() {
        repository.upsertSpecs(List.of());
        // No exception, nothing to assert against — an empty call must not even open a connection.
    }

    @Test
    void anEmptySpecWritesEveryNullableColumnAsNull() throws Exception {
        String url = insertarProducto("empty");

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, TechSpecs.EMPTY)));

        Map<String, Object> fila = leerFila(url);
        assertThat(fila.get("socket_id")).isNull();
        assertThat(fila.get("ddr_id")).isNull();
        assertThat(fila.get("form_factor_id")).isNull();
        assertThat(fila.get("tipo_memoria_id")).isNull();
        assertThat(fila.get("certificacion_id")).isNull();
        assertThat(fila.get("gama_id")).isNull();
        assertThat(fila.get("tipo_almacenamiento_id")).isNull();
        assertThat(fila.get("watts")).isNull();
        assertThat(fila.get("capacidad_gb")).isNull();
        assertThat(fila.get("velocidad_mhz")).isNull();
        assertThat(fila.get("marca_chip_id")).isNull();
        assertThat(fila.get("chipset_tier_id")).isNull();
        assertThat(fila.get("tipo_cooler_id")).isNull();
        assertThat(fila.get("generacion")).isNull();
        assertThat(fila.get("modulos")).isNull();
        assertThat(fila.get("wifi")).isNull();
    }

    // ── pc-builder-deep-taxonomy T5b: marcaChip/chipsetTier/tipoCooler/generacion/modulos/wifi ──

    @Test
    void unaCpuEscribeMarcaChipYGeneracionPeroNoWifiPorNoSerMotherboard() throws Exception {
        String url = insertarProducto("cpu-marca-gen");
        TechSpecs specs = new TechSpecs("AM5", "", "", 0, 0, "", Gama.ALTA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "AMD", 5, 0, 0, false);

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, "CPU", specs)));

        Map<String, Object> fila = leerFila(url);
        assertThat(fila.get("generacion")).isEqualTo(5);
        assertThat(fila.get("wifi"))
                .as("wifi=false from a non-Motherboard reader is a default, never an affirmation")
                .isNull();
        assertThat(nombreResuelto(url, "marca_chip", "marca_chip_id")).isEqualTo("AMD");
    }

    @Test
    void unaMotherboardEscribeTierChipsetYWifiAfirmado() throws Exception {
        String url = insertarProducto("mother-tier-wifi");
        TechSpecs specs = new TechSpecs("AM5", "DDR5", "MATX", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "AMD", 0, 1, 0, true);

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, "Motherboard", specs)));

        assertThat(nombreResuelto(url, "chipset_tier", "chipset_tier_id")).isEqualTo("X_Z");
        assertThat(leerFila(url).get("wifi")).isEqualTo(true);
    }

    @Test
    void unaMotherboardSinWifiEscribeFalseNoNull() throws Exception {
        String url = insertarProducto("mother-no-wifi");
        TechSpecs specs = new TechSpecs("AM4", "DDR4", "ATX", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "AMD", 0, 2, 0, false);

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, "Motherboard", specs)));

        assertThat(leerFila(url).get("wifi"))
                .as("false from the Motherboard reader IS an affirmation — D2's exception")
                .isEqualTo(false);
        assertThat(nombreResuelto(url, "chipset_tier", "chipset_tier_id")).isEqualTo("B");
    }

    @Test
    void unaRamEscribeModulos() throws Exception {
        String url = insertarProducto("ram-modulos");
        TechSpecs specs = new TechSpecs("", "DDR5", "", 0, 32, "DIMM", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                6000, TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 2, false);

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, "RAM", specs)));

        assertThat(leerFila(url).get("modulos")).isEqualTo(2);
    }

    @Test
    void unCoolerEscribeTipoCooler() throws Exception {
        String url = insertarProducto("cooler-tipo");
        TechSpecs specs = new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false, TipoCooler.LIQUIDO);

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, "Cooler", specs)));

        assertThat(nombreResuelto(url, "tipo_cooler", "tipo_cooler_id")).isEqualTo("LIQUIDO");
    }

    private String nombreResuelto(String url, String tabla, String columnaId) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(("""
                     SELECT l.nombre FROM producto_tech_specs p
                     JOIN %s l ON l.id = p.%s
                     WHERE p.url = ?
                     """).formatted(tabla, columnaId))) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row for " + url).isTrue();
                return rs.getString(1);
            }
        }
    }

    @Test
    void aFullSpecResolvesEveryLookupToTheRightName() throws Exception {
        String url = insertarProducto("full");
        TechSpecs specs = new TechSpecs("AM5", "DDR5", "ATX", 750, 32, "DIMM",
                Gama.ALTA, Certificacion.GOLD, 6000, TipoAlmacenamiento.NVME);

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, specs)));

        Map<String, String> nombres = leerNombresResueltos(url);
        assertThat(nombres.get("socket")).isEqualTo("AM5");
        assertThat(nombres.get("ddr")).isEqualTo("DDR5");
        assertThat(nombres.get("form_factor")).isEqualTo("ATX");
        assertThat(nombres.get("tipo_memoria")).isEqualTo("DIMM");
        assertThat(nombres.get("certificacion")).isEqualTo("GOLD");
        assertThat(nombres.get("tipo_almacenamiento")).isEqualTo("NVME");

        Map<String, Object> fila = leerFila(url);
        assertThat(fila.get("watts")).isEqualTo(750);
        assertThat(fila.get("capacidad_gb")).isEqualTo(32);
        assertThat(fila.get("velocidad_mhz")).isEqualTo(6000);
    }

    @Test
    void gamaBajaLandsAsEconomica() throws Exception {
        String url = insertarProducto("baja");
        TechSpecs specs = new TechSpecs("", "", "", 0, 0, "", Gama.BAJA, Certificacion.NINGUNA);

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url, specs)));

        assertThat(leerNombresResueltos(url).get("gama")).isEqualTo("ECONOMICA");
    }

    @Test
    void reUpsertOfTheSameUrlUpdatesInPlace() throws Exception {
        String url = insertarProducto("reupsert");
        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url,
                new TechSpecs("AM4", "DDR4", "", 0, 0, "", Gama.MEDIA, Certificacion.NINGUNA))));

        repository.upsertSpecs(List.of(new TechSpecsPort.SpecsDeProducto(url,
                new TechSpecs("AM5", "DDR5", "", 0, 0, "", Gama.ALTA, Certificacion.NINGUNA))));

        assertThat(filas(url)).as("re-upsert must not create a second row").isEqualTo(1);
        Map<String, String> nombres = leerNombresResueltos(url);
        assertThat(nombres.get("socket")).isEqualTo("AM5");
        assertThat(nombres.get("ddr")).isEqualTo("DDR5");
        assertThat(nombres.get("gama")).isEqualTo("ALTA");
    }

    private String insertarProducto(String slug) throws Exception {
        String url = "https://tech-specs-repo.test/" + slug;
        try (Connection c = dataSource().getConnection()) {
            try (Statement seed = c.createStatement()) {
                // `sitio` is seed data, never truncated between tests, but a raw
                // INSERT INTO productos still needs a matching row present —
                // molde: UnownedRowTest/DeleteProductosGlobalGuardTest. Self-seeded
                // so this test doesn't depend on another test class' run order.
                seed.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('Sitio', 'sitio', 'tiendanube', false, NULL, 'historico') "
                        + "ON CONFLICT DO NOTHING");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) "
                            + "VALUES (?, 'Sitio', 'Producto', 1000, 'tecnologia')")) {
                ps.setString(1, url);
                ps.executeUpdate();
            }
        }
        return url;
    }

    private Map<String, Object> leerFila(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM producto_tech_specs WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row for " + url).isTrue();
                var meta = rs.getMetaData();
                Map<String, Object> fila = new java.util.HashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    fila.put(meta.getColumnName(i), rs.getObject(i));
                }
                return fila;
            }
        }
    }

    private Map<String, String> leerNombresResueltos(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT so.nombre AS socket, dd.nombre AS ddr, ff.nombre AS form_factor,
                            tm.nombre AS tipo_memoria, ce.nombre AS certificacion,
                            ga.nombre AS gama, ta.nombre AS tipo_almacenamiento
                     FROM producto_tech_specs p
                     LEFT JOIN socket so ON so.id = p.socket_id
                     LEFT JOIN ddr dd ON dd.id = p.ddr_id
                     LEFT JOIN form_factor ff ON ff.id = p.form_factor_id
                     LEFT JOIN tipo_memoria tm ON tm.id = p.tipo_memoria_id
                     LEFT JOIN certificacion ce ON ce.id = p.certificacion_id
                     LEFT JOIN gama ga ON ga.id = p.gama_id
                     LEFT JOIN tipo_almacenamiento ta ON ta.id = p.tipo_almacenamiento_id
                     WHERE p.url = ?
                     """)) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row for " + url).isTrue();
                return Map.of(
                        "socket", str(rs.getString("socket")),
                        "ddr", str(rs.getString("ddr")),
                        "form_factor", str(rs.getString("form_factor")),
                        "tipo_memoria", str(rs.getString("tipo_memoria")),
                        "certificacion", str(rs.getString("certificacion")),
                        "gama", str(rs.getString("gama")),
                        "tipo_almacenamiento", str(rs.getString("tipo_almacenamiento")));
            }
        }
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private int filas(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM producto_tech_specs WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
