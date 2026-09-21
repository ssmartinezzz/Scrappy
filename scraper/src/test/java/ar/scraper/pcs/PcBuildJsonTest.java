package ar.scraper.pcs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link PcBuildJson} — null strings serialize as "", specs block is nested per pick. */
class PcBuildJsonTest {

    @Test
    @DisplayName("null pick/spec strings serialize as empty strings, specs block nested")
    void nullStringsBecomeEmpty() {
        PcPick pick = new PcPick("mother", null, "Board X", 100_000, null, null, null,
                new TechSpecs("AM5", "DDR5", "MATX", 0, 0, ""));
        PcBuild build = new PcBuild(List.of(pick), List.of("gpu"), List.of("cpu"), 500_000, 100_000);

        ObjectNode json = PcBuildJson.toJson(build);

        ObjectNode p = (ObjectNode) json.get("picks").get(0);
        assertThat(p.get("sitio").asText()).isEqualTo("");
        assertThat(p.get("url").asText()).isEqualTo("");
        assertThat(p.get("img").asText()).isEqualTo("");
        assertThat(p.get("marca").asText()).isEqualTo("");
        assertThat(p.get("specs").get("socket").asText()).isEqualTo("AM5");
        assertThat(p.get("specs").get("ddr").asText()).isEqualTo("DDR5");
        assertThat(json.get("sinStock").get(0).asText()).isEqualTo("gpu");
        assertThat(json.get("sinCompatible").get(0).asText()).isEqualTo("cpu");
        assertThat(json.get("presupuesto").asDouble()).isEqualTo(500_000);
        assertThat(json.get("totalEstimado").asDouble()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("mensajes serializes as an object keyed by slot, empty object when none (pc-builder-gama T6)")
    void mensajesSerializesAsObjectKeyedBySlot() {
        PcBuild sinMensajes = new PcBuild(List.of(), List.of("gpu"), List.of("cpu"), 0, 0);
        ObjectNode json = PcBuildJson.toJson(sinMensajes);
        assertThat(json.get("mensajes").isObject()).isTrue();
        assertThat(json.get("mensajes").size()).isEqualTo(0);

        PcBuild conMensajes = new PcBuild(List.of(), List.of(), List.of("cpu"), 0, 0,
                Map.of("cpu", "ninguna CPU alcanza la gama pedida"));
        ObjectNode jsonConMensajes = PcBuildJson.toJson(conMensajes);
        assertThat(jsonConMensajes.get("mensajes").get("cpu").asText())
                .isEqualTo("ninguna CPU alcanza la gama pedida");
    }

    @Test
    @DisplayName("specs block includes gama, certificacion, velocidadMhz and tipoAlmacenamiento (pc-builder-gama T6)")
    void specsIncludesTheFourNewFields() {
        PcPick pick = new PcPick("cpu", "Sitio", "CPU X", 400_000, "https://t/cpu", "https://img/x.jpg", "Marca",
                new TechSpecs("", "", "", 0, 0, "", Gama.ALTA, Certificacion.GOLD, 6000, TipoAlmacenamiento.NVME));
        PcBuild build = new PcBuild(List.of(pick), List.of(), List.of(), 0, 400_000);

        ObjectNode specs = (ObjectNode) PcBuildJson.toJson(build).get("picks").get(0).get("specs");

        assertThat(specs.get("gama").asText()).isEqualTo("ALTA");
        assertThat(specs.get("certificacion").asText()).isEqualTo("GOLD");
        assertThat(specs.get("velocidadMhz").asInt()).isEqualTo(6000);
        assertThat(specs.get("tipoAlmacenamiento").asText()).isEqualTo("NVME");
    }

    @Test
    @DisplayName("specs block includes marcaChip, generacion, tierChipset, modulos and wifi (pc-builder-deep-taxonomy T3c)")
    void specsIncludesTheFiveNewFields() {
        PcPick pick = new PcPick("mother", "Sitio", "Mother X", 200_000, "https://t/m", "https://img/m.jpg", "Marca",
                new TechSpecs("AM5", "DDR5", "MATX", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                        0, TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "AMD", 5, 1, 2, true));
        PcBuild build = new PcBuild(List.of(pick), List.of(), List.of(), 0, 200_000);

        ObjectNode specs = (ObjectNode) PcBuildJson.toJson(build).get("picks").get(0).get("specs");

        assertThat(specs.get("marcaChip").asText()).isEqualTo("AMD");
        assertThat(specs.get("generacion").asInt()).isEqualTo(5);
        assertThat(specs.get("tierChipset").asInt()).isEqualTo(1);
        assertThat(specs.get("modulos").asInt()).isEqualTo(2);
        assertThat(specs.get("wifi").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("specs block includes tipoCooler (pc-builder-deep-taxonomy T4d-2)")
    void specsIncludesTipoCooler() {
        PcPick pick = new PcPick("cooler", "Sitio", "Cooler X", 30_000, "https://t/c", "https://img/c.jpg", "Marca",
                new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                        0, TipoAlmacenamiento.DESCONOCIDO, java.util.List.of(), "", 0, 0, 0, false,
                        TipoCooler.LIQUIDO));
        PcBuild build = new PcBuild(List.of(pick), List.of(), List.of(), 0, 30_000);

        ObjectNode specs = (ObjectNode) PcBuildJson.toJson(build).get("picks").get(0).get("specs");

        assertThat(specs.get("tipoCooler").asText()).isEqualTo("LIQUIDO");
    }
}
