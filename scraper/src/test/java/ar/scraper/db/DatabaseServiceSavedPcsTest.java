package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.PcPick;
import ar.scraper.pcs.TechSpecs;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("PC builder")
@Story("Saved PCs")
@DisplayName("DatabaseService — saved PCs CRUD")
class DatabaseServiceSavedPcsTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = new DatabaseService(dataSource());
    }

    private PcPick pick(String slot, String url) {
        return new PcPick(slot, "TestSitio", "Parte de prueba", 100000.0, url, "https://img/x.jpg",
                "MarcaTest", new TechSpecs("AM5", "DDR5", "ATX", 650, 32, "DIMM"));
    }

    @Test
    void guardarPcReturnsPositiveId() {
        int id = db.guardarPc(yo(), "Test", List.of(pick("mother", "https://t/mb")), 500000.0, false, 250000.0, null);

        assertThat(id).isGreaterThan(0);
    }

    @Test
    void guardarPcAppearsInObtenerPcsGuardadas() {
        db.guardarPc(yo(), "Test", List.of(pick("mother", "https://t/mb")), 500000.0, true, 250000.0, null);

        List<Map<String, Object>> list = db.obtenerPcsGuardadas(yo());

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("nombre")).isEqualTo("Test");
        assertThat(list.get(0).get("totalEstimado")).isEqualTo(250000.0);
        assertThat(list.get(0).get("presupuesto")).isEqualTo(500000.0);
        assertThat(list.get(0).get("conGpu")).isEqualTo(true);
    }

    @Test
    void guardarPcPersistsPicksWithSpecs() {
        db.guardarPc(yo(), "Test", List.of(pick("mother", "https://t/mb")), 500000.0, false, 250000.0, null);

        List<Map<String, Object>> list = db.obtenerPcsGuardadas(yo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picks = (List<Map<String, Object>>) list.get(0).get("picks");

        assertThat(picks).hasSize(1);
        assertThat(picks.get(0).get("slot")).isEqualTo("mother");
        @SuppressWarnings("unchecked")
        Map<String, Object> specs = (Map<String, Object>) picks.get(0).get("specs");
        assertThat(specs.get("socket")).isEqualTo("AM5");
        assertThat(specs.get("watts")).isEqualTo(650);
    }

    @Test
    void guardarPcSkipsPicksWithBlankUrl() {
        PcPick sinUrl = new PcPick("cpu", "TestSitio", "Sin url", 1000.0, "", "", "", TechSpecs.EMPTY);
        db.guardarPc(yo(), "Test", List.of(pick("mother", "https://t/mb"), sinUrl), 500000.0, false, 250000.0, null);

        List<Map<String, Object>> list = db.obtenerPcsGuardadas(yo());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> picks = (List<Map<String, Object>>) list.get(0).get("picks");

        assertThat(picks).hasSize(1);
    }

    @Test
    void obtenerPcsGuardadasReturnsMostRecentFirst() throws InterruptedException {
        db.guardarPc(yo(), "PC A", List.of(), 0.0, false, 0.0, null);
        Thread.sleep(1001); // ensure different created_at (second-precision timestamps)
        db.guardarPc(yo(), "PC B", List.of(), 0.0, false, 0.0, null);

        List<Map<String, Object>> list = db.obtenerPcsGuardadas(yo());

        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("nombre")).isEqualTo("PC B");
    }

    @Test
    void eliminarPcGuardadaReturnsTrueAndRemovesFromList() {
        int id = db.guardarPc(yo(), "Para borrar", List.of(), 0.0, false, 0.0, null);

        boolean result = db.eliminarPcGuardada(yo(), id);

        assertThat(result).isTrue();
        assertThat(db.obtenerPcsGuardadas(yo())).isEmpty();
    }

    @Test
    void eliminarPcGuardadaReturnsFalseForNonExistentId() {
        Allure.parameter("id", 9999);
        boolean result = db.eliminarPcGuardada(yo(), 9999);

        assertThat(result).isFalse();
    }

    @Test
    void renombrarPcUpdatesNombreInList() {
        int id = db.guardarPc(yo(), "Viejo", List.of(), 0.0, false, 0.0, null);

        boolean renamed = db.renombrarPc(yo(), id, "Nuevo");

        assertThat(renamed).isTrue();
        assertThat(db.obtenerPcsGuardadas(yo()).get(0).get("nombre")).isEqualTo("Nuevo");
    }

    @Test
    void renombrarPcReturnsFalseForNonExistentId() {
        Allure.parameter("id", 9999);
        boolean result = db.renombrarPc(yo(), 9999, "x");

        assertThat(result).isFalse();
    }

    // ── gama (pc-builder-gama T6) ───────────────────────────────────────────

    @Test
    void guardarPcPersistsGamaAndAppearsInObtenerPcsGuardadas() {
        db.guardarPc(yo(), "Test", List.of(pick("cpu", "https://t/cpu")), 500000.0, true, 250000.0, Gama.ALTA);

        List<Map<String, Object>> list = db.obtenerPcsGuardadas(yo());

        assertThat(list.get(0).get("gama")).isEqualTo("alta");
    }

    @Test
    void guardarPcSinGamaDejaGamaNula() {
        db.guardarPc(yo(), "Test", List.of(pick("cpu", "https://t/cpu")), 0.0, false, 0.0, null);

        List<Map<String, Object>> list = db.obtenerPcsGuardadas(yo());

        assertThat(list.get(0).get("gama")).isNull();
    }

    @Test
    void guardarPcConGamaDesconocidaDejaGamaNula() {
        db.guardarPc(yo(), "Test", List.of(pick("cpu", "https://t/cpu")), 0.0, false, 0.0, Gama.DESCONOCIDA);

        List<Map<String, Object>> list = db.obtenerPcsGuardadas(yo());

        assertThat(list.get(0).get("gama")).isNull();
    }

    /**
     * @see DatabaseServiceSavedOutfitsTest#yo() — mismo motivo: seedear en cada
     * llamada es correcto porque {@code PostgresTestBase} truncatea entre tests.
     */
    private UUID yo() {
        return UsuarioDePrueba.yo(dataSource());
    }
}
