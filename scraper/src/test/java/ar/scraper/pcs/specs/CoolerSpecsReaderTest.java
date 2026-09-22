package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;
import ar.scraper.pcs.TipoCooler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoolerSpecsReader — sockets supported, off the cooler's own name (T2d)")
class CoolerSpecsReaderTest {

    private final CoolerSpecsReader reader = new CoolerSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsCooler() {
        assertThat(reader.categoria()).isEqualTo("Cooler");
    }

    @Test
    void abstieneCuandoNoNombraNingunSocket() {
        assertThat(leer("Cooler Cooler Master Hyper 212").socketsSoportados()).isEmpty();
    }

    @Test
    void abstieneCuandoSoloDiceIntelOAmdSinSocket() {
        // Medido: 45 nombres dicen "intel"/"amd" pelado sin socket explícito —
        // no cuenta como señal, D6 exige que se pueda leer el socket en sí.
        assertThat(leer("Cooler CPU AMD Intel Universal RGB").socketsSoportados()).isEmpty();
    }

    @Test
    void leeUnSoloSocketExplicito() {
        assertThat(leer("Bracket Cooler Master soporte para fan cooler LGA1700").socketsSoportados())
                .containsExactly("LGA1700");
    }

    @Test
    void leeVariosSocketsEnUnSoloNombre() {
        // Nombre real del catálogo: "Fan Cooler CPU Kelyx 95W AMD-Intel AM4 AM5 1200 1700"
        assertThat(leer("Fan Cooler CPU Kelyx 95W AMD-Intel AM4 AM5 1200 1700").socketsSoportados())
                .containsExactlyInAnyOrder("AM4", "AM5", "LGA1200", "LGA1700");
    }

    @Test
    void leeAm3() {
        assertThat(leer("Cooler CPU Deepcool Gammaxx 400 AM3").socketsSoportados())
                .containsExactly("AM3");
    }

    @Test
    void leeLga1851() {
        assertThat(leer("Cooler CPU Deepcool AK400 LGA1851").socketsSoportados())
                .containsExactly("LGA1851");
    }

    @Test
    void bareDigitTokenLga1151() {
        assertThat(leer("Cooler CPU Universal Socket 1151").socketsSoportados())
                .containsExactly("LGA1151");
    }

    @Test
    void formaCompacta115xMapeaSoloALga1151() {
        assertThat(leer("Cooler CPU Universal 115x Compatible").socketsSoportados())
                .containsExactly("LGA1151");
    }

    @Test
    void soloLlenaSocketsSoportadosElRestoAbstiene() {
        TechSpecs t = leer("Cooler CPU Deepcool AG400 AM4 AM5 LGA1700");

        assertThat(t.socket()).isEmpty();
        assertThat(t.ddr()).isEmpty();
        assertThat(t.formFactor()).isEmpty();
        assertThat(t.watts()).isZero();
    }

    // ── tipoCooler (T4d-2): LIQUIDO/AIRE/DESCONOCIDO off the cooler's name ──

    @Test
    void aguaExplicitaEsLiquido() {
        assertThat(leer("CPU Water Cooler Lovingcool 240mm AK-B240-03 - ARGB - Black").tipoCooler())
                .isEqualTo(TipoCooler.LIQUIDO);
    }

    @Test
    void nombreConCoolerEsAire() {
        assertThat(leer("CPU Cooler Cooler Master DT621 R1").tipoCooler())
                .isEqualTo(TipoCooler.AIRE);
    }

    @Test
    void disipadorParaCpuEsAire() {
        assertThat(leer("Cooler para CPU Intel/AMD Deepcool AG400").tipoCooler())
                .isEqualTo(TipoCooler.AIRE);
    }

    @Test
    void unFanDeGabineteNoEsUnCoolerDeCpu() {
        // Líder "fan" con diámetro de fan (120mm), no un radiador de AIO ni
        // un cooler de CPU nombrado como tal.
        assertThat(leer("Fan Cooler 120mm Lovingcool DZPKK-120-Logo - Black").tipoCooler())
                .isEqualTo(TipoCooler.DESCONOCIDO);
    }

    @Test
    void unPanoDeLimpiezaParaPastaTermicaNoEsUnCooler() {
        assertThat(leer("Paño de limpieza Arctic para Pasta térmica - Cleaner activo - por unidad").tipoCooler())
                .isEqualTo(TipoCooler.DESCONOCIDO);
    }

    @Test
    void unaPastaTermicaNoEsUnCooler() {
        assertThat(leer("Pasta Térmica Arctic MX-4 4g").tipoCooler())
                .isEqualTo(TipoCooler.DESCONOCIDO);
    }
}
