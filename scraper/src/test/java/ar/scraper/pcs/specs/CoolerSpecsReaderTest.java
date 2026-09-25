package ar.scraper.pcs.specs;

import ar.scraper.pcs.ClaseDisipador;
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

    // ── radiador: fase 9 (pc-builder-fine-grained-prefs T1) ──────────────

    @Test
    void leeElRadiadorDeUnaRefrigeracionLiquida() {
        // 84 de los 171 líquidos del catálogo declaran radiador (medido 2026-09-22).
        assertThat(leer("CPU Water Cooler Lovingcool 360mm AK-B360-03 - Argb - Black").radiadorMm())
                .isEqualTo(360);
        assertThat(leer("Water Cooling Sharkoon S70 Rgb Aio 240mm 600rpm A 2000rpm").radiadorMm())
                .isEqualTo(240);
    }

    @Test
    void unNumeroSueltoQueNoLleveMmNoEsUnRadiador() {
        // "Masterliquid 360 Core" y "Th240" dejan un 360/un th240 dando
        // vueltas: sólo el token entero digitos+mm cuenta, misma política
        // que la capacidad de AlmacenamientoSpecsReader.
        assertThat(leer("Water Cooling Thermaltake Th240 V2 Argb Sync Aio Snow White").radiadorMm())
                .isZero();
    }

    @Test
    void elDiametroDeUnFanDeGabineteNoEsUnRadiador() {
        // El líder "fan" ya abstiene el tipo; el radiador tiene que abstener
        // por el mismo motivo o un fan de 120mm rankearía como AIO chico.
        assertThat(leer("Fan Cooler 120mm Lovingcool DZPKK-120-Logo - Black").radiadorMm())
                .isZero();
    }

    @Test
    void unCoolerDeAireNoDeclaraRadiador() {
        assertThat(leer("CPU Cooler Cooler Master DT621 R1").radiadorMm()).isZero();
    }

    @Test
    void unFanDeGabineteQueLideraConCoolerTampocoEsUnCoolerDeCpu() {
        // El guard de la fase 7 mira SÓLO el primer token, así que ataja
        // "Fan Cooler 120mm..." y deja pasar "Cooler Fan 120mm...", que es el
        // mismo producto con las dos palabras al revés. Medido sobre la dev DB
        // (2026-09-22): las 6 filas activas que lideran con "cooler fan" son
        // fans de gabinete de 120/140mm, ninguna es un cooler de CPU — y la
        // más barata ($7.250) ganaba el slot cuando se pedía refrigeración
        // por aire.
        assertThat(leer("Cooler Fan ID-Cooling FL-12025 White").tipoCooler())
                .isEqualTo(TipoCooler.DESCONOCIDO);
        assertThat(leer("Cooler FAN Be Quiet! LIGHT WINGS LX 140mm ARGB PWM").tipoCooler())
                .isEqualTo(TipoCooler.DESCONOCIDO);
        assertThat(leer("Cooler Fan Cooler Master Halo 120 3en1 White").tipoCooler())
                .isEqualTo(TipoCooler.DESCONOCIDO);
    }

    @Test
    void unCoolerDeCpuQueNombraUnFanMasAdelanteSigueSiendoUnCooler() {
        // El par tiene que ser ADYACENTE y al principio: acá "fan" aparece,
        // pero el producto se nombra a sí mismo como cooler de CPU primero.
        assertThat(leer("Cooler CPU Deepcool AK400 con Fan de 120mm").tipoCooler())
                .isEqualTo(TipoCooler.AIRE);
        assertThat(leer("Cooler Master Hyper 212 Black Edition").tipoCooler())
                .isEqualTo(TipoCooler.AIRE);
    }

    // ── T15, pc-builder-homelab: AIOs de ASUS sin "cooler" ni radiador "mm" ──

    @Test
    @DisplayName("T15: los AIO de ASUS con el token \"lc\" + un radiador sin \"mm\" son LIQUIDO")
    void asusLcConRadiadorSinMmEsLiquido() {
        assertThat(leer("Cooler CPU ASUS PRIME LC 240 ARGB").tipoCooler()).isEqualTo(TipoCooler.LIQUIDO);
        assertThat(leer("ASUS TUF LC III 240").tipoCooler()).isEqualTo(TipoCooler.LIQUIDO);
        assertThat(leer("ASUS ROG STRIX LC III 360").tipoCooler()).isEqualTo(TipoCooler.LIQUIDO);
    }

    @Test
    @DisplayName("T15: la serie ROG Ryuo/Ryujin es LIQUIDO aunque el nombre no diga \"cooler\"")
    void rogRyuoYRyujinSonLiquido() {
        assertThat(leer("ROG RYUO 3 240").tipoCooler()).isEqualTo(TipoCooler.LIQUIDO);
        assertThat(leer("ROG RYUJIN III 360").tipoCooler()).isEqualTo(TipoCooler.LIQUIDO);
    }

    @Test
    @DisplayName("T15: esos mismos AIO de ASUS leen su radiador aunque el número no lleve \"mm\"")
    void asusLeeElRadiadorSinSufijoMm() {
        assertThat(leer("Cooler CPU ASUS PRIME LC 240 ARGB").radiadorMm()).isEqualTo(240);
        assertThat(leer("ASUS TUF LC III 240").radiadorMm()).isEqualTo(240);
        assertThat(leer("ASUS ROG STRIX LC III 360").radiadorMm()).isEqualTo(360);
        assertThat(leer("ROG RYUO 3 240").radiadorMm()).isEqualTo(240);
        assertThat(leer("ROG RYUJIN III 360").radiadorMm()).isEqualTo(360);
    }

    // ── T16, pc-builder-homelab: profundidad del eje AIRE ────────────────

    @Test
    @DisplayName("T16: clase doble torre — nombres reales medidos en la dev DB")
    void claseDobleTorrePorSerieMedida() {
        assertThat(leer("Cooler CPU Be Quiet! DARK ROCK PRO 5").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
        assertThat(leer("Cooler CPU Be Quiet! DARK ROCK ELITE").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
        assertThat(leer("Cooler CPU DeepCool ASSASSIN IV VC Vision Display").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
        assertThat(leer("Cooler CPU ID-Cooling FROZN A620 PRO SE").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
        assertThat(leer("Cooler CPU ID-Cooling FROZN A610 Black").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
        assertThat(leer("CPU Cooler Cooler Master Hyper 612 APEX Black").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
        assertThat(leer("CPU Cooler Cooler Master DT621 R1").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
        assertThat(leer("CPU Cooler Thermaltake ASTRIA 600 Lighting ARGB Black").claseDisipador())
                .isEqualTo(ClaseDisipador.DOBLE_TORRE);
    }

    @Test
    @DisplayName("T16: clase torre — nombres reales medidos en la dev DB")
    void claseTorrePorSerieMedida() {
        assertThat(leer("Cooler CPU ID-Cooling SE-214-XT").claseDisipador()).isEqualTo(ClaseDisipador.TORRE);
        assertThat(leer("Cooler CPU ID-Cooling FROZN A410 Black").claseDisipador()).isEqualTo(ClaseDisipador.TORRE);
        assertThat(leer("Cooler Cpu Thermaltake Pure Rock Black").claseDisipador()).isEqualTo(ClaseDisipador.TORRE);
        assertThat(leer("Cooler CPU Be Quiet! DARK ROCK 5").claseDisipador()).isEqualTo(ClaseDisipador.TORRE);
        assertThat(leer("CPU Cooler Thermaltake ASTRIA 400 ARGB Black").claseDisipador())
                .isEqualTo(ClaseDisipador.TORRE);
        assertThat(leer("Cpu Cooler Thermaltake UX500 Black ARGB").claseDisipador()).isEqualTo(ClaseDisipador.TORRE);
        assertThat(leer("Cooler CPU Cooler Master Hyper 212 Black Edition").claseDisipador())
                .isEqualTo(ClaseDisipador.TORRE);
    }

    @Test
    @DisplayName("T16: sin señal de serie ni marcador explícito, la clase abstiene")
    void claseAbstieneSinSenal() {
        assertThat(leer("CPU Cooler Raptor Cryo RGB - 3P").claseDisipador()).isEqualTo(ClaseDisipador.DESCONOCIDA);
    }

    @Test
    @DisplayName("T16: un cooler líquido nunca tiene clase de disipador de aire")
    void unLiquidoNoTieneClaseDeAire() {
        assertThat(leer("CPU Water Cooler Lovingcool 240mm AK-B240-03 - ARGB - Black").claseDisipador())
                .isEqualTo(ClaseDisipador.DESCONOCIDA);
    }

    @Test
    @DisplayName("T16: heatpipes — \"NHdp\"/\"Nh\" y \"N heatpipes\", nombres reales")
    void heatpipesSeLeenDelNombre() {
        assertThat(leer("CPU Cooler Cooler Master Hyper 212 3HDP ARGB - Black").heatpipes()).isEqualTo(3);
        assertThat(leer("Cooler Cpu Evolabs Cryo Pro 4h").heatpipes()).isEqualTo(4);
        assertThat(leer("Cooler CPU Deepcool AK620 6 Heatpipes").heatpipes()).isEqualTo(6);
    }

    @Test
    @DisplayName("T16: sin ninguna forma de heatpipe en el nombre, abstiene")
    void heatpipesAbstieneSinSenal() {
        assertThat(leer("CPU Cooler Raptor Cryo RGB - 3P").heatpipes()).isZero();
    }
}
