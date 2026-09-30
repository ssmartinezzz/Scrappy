package ar.scraper.db;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.db.support.FaultInjection;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.pcs.PcPick;
import ar.scraper.pcs.TechSpecs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each write unit is atomic by declaration. A failure injected in the database part-way through
 * a unit must leave none of the unit's earlier writes behind, and the method keeps returning
 * its sentinel instead of throwing.
 */
@Epic("Persistence")
@Feature("Transactions")
@Story("Repository write units roll back as a whole")
@DisplayName("Repository write units — rollback when a later statement fails")
class TransactionalUnitsRollbackTest extends PostgresTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
    }

    private UUID yo() {
        return UsuarioDePrueba.yo(dataSource());
    }

    private long count(String table) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private PcPick pick(String url) {
        return new PcPick("mother", "TestSitio", "Parte de prueba", 100000.0, url, "https://img/x.jpg",
                "MarcaTest", new TechSpecs("AM5", "DDR5", "ATX", 650, 32, "DIMM"));
    }

    @Test
    @DisplayName("guardarPc leaves no header when the items fail")
    void guardarPcLeavesNoHeader() throws Exception {
        try (var fault = FaultInjection.raiseOn(dataSource(), "saved_pc_item", "INSERT", null)) {
            int id = db.guardarPc(yo(), "PC", List.of(pick("https://t/mb")), 500000.0, false, 250000.0, null);

            assertThat(id).isEqualTo(-1);
        }
        assertThat(count("saved_pcs")).isZero();
    }

    @Test
    @DisplayName("guardarOutfit leaves no header when the items fail")
    void guardarOutfitLeavesNoHeader() throws Exception {
        try (var fault = FaultInjection.raiseOn(dataSource(), "saved_outfit_item", "INSERT", null)) {
            int id = db.guardarOutfit(yo(), "Outfit", "[{\"slot\":\"torso\",\"url\":\"https://t/x\"}]", null, 1000.0);

            assertThat(id).isEqualTo(-1);
        }
        assertThat(count("saved_outfits")).isZero();
    }

    @Test
    @DisplayName("deleteCronJob keeps the executions when deleting the job fails")
    void deleteCronJobKeepsExecutions() throws Exception {
        long job = db.insertCronJob("Nightly", 0, 0, List.of("Freres"), false, false, "0 0 3 * * *", true, null);
        db.insertCronExecution(job, "2026-07-05T03:00:00", "success", null);
        try (var fault = FaultInjection.raiseOn(dataSource(), "cron_jobs", "DELETE", null)) {
            assertThat(db.deleteCronJob(job)).isFalse();
        }
        assertThat(count("cron_executions")).isEqualTo(1);
        assertThat(db.getCronJob(job)).isPresent();
    }

    @Test
    @DisplayName("insertCronJob leaves no job when its site list fails")
    void insertCronJobLeavesNoJobWithoutItsSites() throws Exception {
        try (var fault = FaultInjection.raiseOn(dataSource(), "cron_job_sitio", "INSERT", null)) {
            long id = db.insertCronJob("Nightly", 0, 0, List.of("Freres"), false, false, "0 0 3 * * *", true, null);

            assertThat(id).isEqualTo(-1);
        }
        assertThat(count("cron_jobs")).isZero();
    }

    @Test
    @DisplayName("updateCronJob keeps the previous sites when the new list fails")
    void updateCronJobKeepsPreviousSites() throws Exception {
        long id = db.insertCronJob("Nightly", 0, 0, List.of("Freres"), false, false, "0 0 3 * * *", true, null);
        try (var fault = FaultInjection.raiseOn(dataSource(), "cron_job_sitio", "INSERT", null)) {
            boolean ok = db.updateCronJob(id, "Renamed", 0, 0, List.of("VCP"), false, false, "0 0 3 * * *", true, null);

            assertThat(ok).isFalse();
        }
        var job = db.getCronJob(id).orElseThrow();
        assertThat(job.name()).isEqualTo("Nightly");
        assertThat(job.sitios()).containsExactly("Freres");
    }

    @Test
    @DisplayName("activarPreset on a missing id keeps the previous active preset")
    void activarPresetOnMissingIdKeepsPrevious() {
        int active = db.crearPreset("Actual", 10.0, 6);
        assertThat(db.activarPreset(active)).isTrue();

        assertThat(db.activarPreset(active + 1000)).isFalse();

        assertThat(db.listarPresets()).filteredOn(p -> p.activo()).extracting(p -> p.id()).containsExactly(active);
    }

    @Test
    @DisplayName("eliminarPreset keeps the last preset when recreating the illustrative one fails")
    void eliminarPresetKeepsLastPresetWhenRecreationFails() throws Exception {
        int only = db.crearPreset("Unico", 10.0, 6);
        try (var fault = FaultInjection.raiseOn(dataSource(), "financiacion_presets", "INSERT", null)) {
            assertThat(db.eliminarPreset(only)).isFalse();
        }
        assertThat(db.listarPresets()).extracting(p -> p.id()).contains(only);
    }

    @Test
    @DisplayName("guardarPreciosExternos keeps today's rows when re-inserting fails")
    void guardarPreciosExternosKeepsPreviousRows() throws Exception {
        db.upsertProductos(List.of(new ar.scraper.model.Product("Sitio", "Producto", 1000.0, null, "https://p/1",
                "http://img.example/x.jpg", "Remera", "unisex", List.of("M"), ar.scraper.model.Product.MlScore.EMPTY,
                "Nike", "indumentaria", false, false, ar.scraper.model.Product.SenalCompra.EMPTY,
                ar.scraper.model.Product.SenalFinanciacion.EMPTY, 1)));
        var row = List.of(java.util.Map.<String, Object>of("titulo", "A", "precio", 10.0, "url", "https://e/a"));
        db.guardarPreciosExternos("https://p/1", "MercadoLibre", row);
        try (var fault = FaultInjection.raiseOn(dataSource(), "precios_externos", "INSERT", null)) {
            db.guardarPreciosExternos("https://p/1", "MercadoLibre", row);
        }
        assertThat(db.cargarPreciosExternos("https://p/1")).hasSize(1);
    }

    @Test
    @DisplayName("guardarCategoriaStats persists nothing when one category fails")
    void guardarCategoriaStatsIsAllOrNothing() throws Exception {
        List<String> categorias = new ArrayList<>(CategoryGroups.canonicalCategories());
        JsonNode stats = MAPPER.createObjectNode()
                .set(categorias.get(0), MAPPER.createObjectNode().put("n", 3));
        ((com.fasterxml.jackson.databind.node.ObjectNode) stats)
                .set(categorias.get(1), MAPPER.createObjectNode().put("n", 4));
        try (var fault = FaultInjection.raiseOn(dataSource(), "categoria_stats", "INSERT",
                "NEW.categoria = '" + categorias.get(1).replace("'", "''") + "'")) {
            db.guardarCategoriaStats(stats);
        }
        assertThat(count("categoria_stats")).isZero();
    }

    @Test
    @DisplayName("guardarMlOutput keeps the previous output when trimming old ones fails")
    void guardarMlOutputKeepsPreviousWhenTrimFails() throws Exception {
        for (int i = 1; i <= 10; i++) db.guardarMlOutput(mlOutput(i));
        try (var fault = FaultInjection.raiseOn(dataSource(), "ml_output", "DELETE", null)) {
            db.guardarMlOutput(mlOutput(11));
        }
        assertThat(count("ml_output")).isEqualTo(10);
        assertThat(db.cargarMlOutput().path("marca").asInt()).isEqualTo(10);
    }

    private JsonNode mlOutput(int marca) {
        var root = MAPPER.createObjectNode().put("marca", marca);
        root.putObject("scores").put("https://p/" + marca, 1);
        root.putObject("tendencias");
        return root;
    }

    @Test
    @DisplayName("guardarSitio writes neither table when the second one fails")
    void guardarSitioWritesNeitherTable() throws Exception {
        try (var fault = FaultInjection.raiseOn(dataSource(), "sitio", "INSERT", "NEW.origen = 'dinamico'")) {
            db.guardarSitio("SitioAtomico", "https://misitio.com", "shopify");
        }
        assertThat(count("sitios_dinamicos")).isZero();
        assertThat(count("sitio WHERE nombre = 'SitioAtomico'")).isZero();
    }

    @Test
    @DisplayName("eliminarSitio keeps the site when re-labelling its origin fails")
    void eliminarSitioKeepsTheSiteWhenRelabellingFails() throws Exception {
        db.guardarSitio("SitioAtomico", "https://misitio.com", "shopify");
        try (var fault = FaultInjection.raiseOn(dataSource(), "sitio", "UPDATE", "NEW.origen = 'historico'")) {
            db.eliminarSitio("SitioAtomico");
        }
        assertThat(count("sitios_dinamicos")).isEqualTo(1);
    }

    // ── scrape runs and products ─────────────────────────────────────────────

    private String text(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private ar.scraper.model.Product producto(String url, double precio) {
        return new ar.scraper.model.Product("Freres", "Producto", precio, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M"), ar.scraper.model.Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, ar.scraper.model.Product.SenalCompra.EMPTY,
                ar.scraper.model.Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    @DisplayName("crearScrapeRun leaves no run when enrolling its sites fails")
    void crearScrapeRunLeavesNoRun() throws Exception {
        try (var fault = FaultInjection.raiseOn(dataSource(), "scrape_run_site", "INSERT", null)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.crearScrapeRun(
                    UUID.randomUUID(), java.time.Instant.now(), null, null, List.of("freres")))
                    .isInstanceOf(ar.scraper.model.PersistenciaException.class);
        }
        assertThat(count("scrape_run")).isZero();
    }

    @Test
    @DisplayName("reabrirScrapeRun keeps the run INTERRUPTED when resetting its sites fails")
    void reabrirKeepsTheRunInterrupted() throws Exception {
        long run = db.crearScrapeRun(UUID.randomUUID(), java.time.Instant.now(), null, null, List.of("freres"));
        db.marcarSitioEnCurso(run, "freres", java.time.Instant.now());
        db.marcarRunsInterrumpidos(java.time.Instant.now());
        try (var fault = FaultInjection.raiseOn(dataSource(), "scrape_run_site", "UPDATE", null)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.reabrirScrapeRun(run))
                    .isInstanceOf(ar.scraper.model.PersistenciaException.class);
        }
        assertThat(text("SELECT status FROM scrape_run WHERE id = " + run)).isEqualTo("INTERRUPTED");
    }

    @Test
    @DisplayName("upsertProductos returns 0 nuevos and keeps every earlier row when the soft-delete step fails")
    void upsertProductosFailureIsAllOrNothing() throws Exception {
        db.upsertParcial(List.of(producto("https://t/a", 1000), producto("https://t/b", 1000)));
        try (var fault = FaultInjection.raiseOn(dataSource(), "productos", "UPDATE", "NEW.activo = false")) {
            var stats = db.upsertProductos(List.of(producto("https://t/a", 2000), producto("https://t/c", 500)));

            assertThat(stats).isEqualTo(new ar.scraper.catalog.UpsertStats(0, 0, 0, 0));
        }
        assertThat(text("SELECT precio::int FROM productos WHERE url = 'https://t/a'")).isEqualTo("1000");
        assertThat(text("SELECT activo::text FROM productos WHERE url = 'https://t/b'")).isEqualTo("true");
        assertThat(count("productos WHERE url = 'https://t/c'")).isZero();
    }

    @Test
    @DisplayName("rows committed per site by upsertParcial survive a later upsertProductos failure")
    void perSiteRowsSurviveAFailedFinalUpsert() throws Exception {
        db.upsertParcial(List.of(producto("https://t/a", 1000)));
        db.upsertParcial(List.of(producto("https://t/b", 1000)));
        try (var fault = FaultInjection.raiseOn(dataSource(), "productos", "UPDATE", "NEW.activo = false")) {
            db.upsertProductos(List.of(producto("https://t/a", 1000)));
        }
        assertThat(count("productos WHERE activo")).isEqualTo(2);
    }

    @Test
    @DisplayName("limpiarProductos keeps the catalog when wiping category stats fails")
    void limpiarProductosKeepsTheCatalogWhenStatsWipeFails() throws Exception {
        db.upsertParcial(List.of(producto("https://t/a", 1000)));
        exec("INSERT INTO categoria_stats (categoria, n, mean, median, mode, std, cv, q1, q3, iqr, mad, fence_low,"
                + " fence_high, updated_at) VALUES ('Remera',1,1,1,1,1,1,1,1,1,1,1,1, now())");
        try (var fault = FaultInjection.raiseOn(dataSource(), "categoria_stats", "DELETE", null)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.limpiarProductos())
                    .isInstanceOf(ar.scraper.model.PersistenciaException.class);
        }
        assertThat(count("productos")).isEqualTo(1);
    }

    @Test
    @DisplayName("actualizarNormalizacion keeps the previous sizes when writing the new ones fails")
    void actualizarNormalizacionKeepsPreviousSizes() throws Exception {
        db.upsertParcial(List.of(producto("https://t/a", 1000)));
        try (var fault = FaultInjection.raiseOn(dataSource(), "producto_talle", "INSERT", null)) {
            int rows = db.actualizarNormalizacion("https://t/a", "Remera", "Nike", "unisex", List.of("L", "XL"), "");

            assertThat(rows).isZero();
        }
        assertThat(text("SELECT string_agg(talle, ',') FROM producto_talle WHERE url = 'https://t/a'")).isEqualTo("M");
    }

    @Test
    @DisplayName("aplicarReclasificacionAuditada changes nothing when the audit row fails")
    void aplicarReclasificacionAuditadaChangesNothingWithoutAudit() throws Exception {
        var previo = producto("https://t/a", 1000);
        db.upsertParcial(List.of(previo));
        try (var fault = FaultInjection.raiseOn(dataSource(), "agent_reclassify_audit", "INSERT", null)) {
            boolean ok = db.aplicarReclasificacionAuditada("https://t/a", "Pantalon", "Nike", "unisex",
                    List.of("M"), "", previo, "tester");

            assertThat(ok).isFalse();
        }
        assertThat(text("SELECT categoria FROM productos WHERE url = 'https://t/a'")).isEqualTo("Remera");
        assertThat(text("SELECT bloqueado_por FROM productos WHERE url = 'https://t/a'")).isNull();
    }
}
