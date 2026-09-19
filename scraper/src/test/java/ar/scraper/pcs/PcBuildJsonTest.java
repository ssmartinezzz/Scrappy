package ar.scraper.pcs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
