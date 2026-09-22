package ar.scraper.pcs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los dos campos que agrega la fase 9 tienen que cruzar el cable: {@code
 * PcsPanel.resumenSpecs} los lee, y un campo que el serializador no escribe
 * es un campo que la UI nunca puede mostrar.
 */
@DisplayName("PcBuildJson — tamanioGabinete y radiadorMm viajan al cliente (fase 9)")
class PcBuildJsonFase9Test {

    private static ObjectNode specsDe(TechSpecs specs) {
        PcPick pick = new PcPick("gabinete", "sitio", "Gabinete", 1000, "https://t/x", "", "", specs);
        ObjectNode json = PcBuildJson.toJson(
                new PcBuild(List.of(pick), List.of(), List.of(), 0, 1000, Map.of()));
        return (ObjectNode) json.get("picks").get(0).get("specs");
    }

    @Test
    void serializaLosDosCamposNuevos() {
        TechSpecs specs = new TechSpecs("", "", "ATX", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false, TipoCooler.LIQUIDO, 0,
                TamanioGabinete.FULL, 360);

        ObjectNode json = specsDe(specs);

        assertThat(json.get("tamanioGabinete").asText()).isEqualTo("FULL");
        assertThat(json.get("radiadorMm").asInt()).isEqualTo(360);
    }

    @Test
    void laAbstencionViajaComoTalYNoComoAusencia() {
        // El cliente distingue por valor, no por presencia: resumenSpecs
        // omite DESCONOCIDO y 0 igual que omite "" y 0 en los demás campos.
        ObjectNode json = specsDe(TechSpecs.EMPTY);

        assertThat(json.get("tamanioGabinete").asText()).isEqualTo("DESCONOCIDO");
        assertThat(json.get("radiadorMm").asInt()).isZero();
    }
}
