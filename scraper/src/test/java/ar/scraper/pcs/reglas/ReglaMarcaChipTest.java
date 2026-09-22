package ar.scraper.pcs.reglas;

import ar.scraper.pcs.Certificacion;
import ar.scraper.pcs.ContextoDeArmado;
import ar.scraper.pcs.Gama;
import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoAlmacenamiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReglaMarcaChip — candidato.marcaChip() vs la marca pedida (CPU/GPU por nombre, mother por socket derivado)")
class ReglaMarcaChipTest {

    private final ContextoDeArmado contexto = ContextoDeArmado.inicial(450);

    private static TechSpecs conMarcaChip(String marcaChip) {
        return new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), marcaChip, 0, 0, 0, false);
    }

    @Test
    @DisplayName("does not filter at all when no marca was requested")
    void noFiltraCuandoNoSePidioMarca() {
        ReglaMarcaChip regla = new ReglaMarcaChip(null);

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isTrue();
    }

    @Test
    @DisplayName("does not veto a candidate whose marcaChip matches the requested one")
    void noVetaCuandoCoincide() {
        ReglaMarcaChip regla = new ReglaMarcaChip("AMD");

        assertThat(regla.permite(conMarcaChip("AMD"), contexto)).isTrue();
    }

    @Test
    @DisplayName("vetoes a candidate whose marcaChip differs from the requested one")
    void vetaCuandoDifiere() {
        ReglaMarcaChip regla = new ReglaMarcaChip("AMD");

        assertThat(regla.permite(conMarcaChip("INTEL"), contexto)).isFalse();
    }

    @Test
    @DisplayName("D2: vetoes on abstention — a candidate with no readable marcaChip when a marca was requested")
    void vetaPorAbstencionCuandoSePidioMarca() {
        ReglaMarcaChip regla = new ReglaMarcaChip("NVIDIA");

        assertThat(regla.permite(TechSpecs.EMPTY, contexto)).isFalse();
    }

    @Test
    @DisplayName("a mother candidate's marcaChip is already derived from socket by the reader — matches transparently")
    void motherUsaLaMarcaDerivadaDelSocket() {
        ReglaMarcaChip regla = new ReglaMarcaChip("AMD");
        // AM5 -> marcaChip("AMD"), as MotherboardSpecsReader already derives (T3b).
        TechSpecs mother = new TechSpecs("AM5", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA, 0,
                TipoAlmacenamiento.DESCONOCIDO, List.of(), "AMD", 0, 0, 0, false);

        assertThat(regla.permite(mother, contexto)).isTrue();
    }

    @Test
    @DisplayName("motivo mentions the requested marca")
    void motivoMencionaLaMarcaPedida() {
        ReglaMarcaChip regla = new ReglaMarcaChip("INTEL");

        assertThat(regla.motivo()).contains("INTEL");
    }
}
