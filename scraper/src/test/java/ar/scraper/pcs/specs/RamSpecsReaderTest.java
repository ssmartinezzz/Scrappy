package ar.scraper.pcs.specs;

import ar.scraper.pcs.TechSpecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RamSpecsReader — velocidadMhz, added in pc-builder-gama T3b (ddr/capacidad/tipoMemoria unchanged)")
class RamSpecsReaderTest {

    private final RamSpecsReader reader = new RamSpecsReader();

    private TechSpecs leer(String nombre) {
        return reader.leer(Tokens.de(nombre));
    }

    @Test
    void categoriaEsRam() {
        assertThat(reader.categoria()).isEqualTo("RAM");
    }

    // ── forma "6000MHz" (un solo token) ──────────────────────────────────

    @Test
    void velocidadAtadaAlSufijoMhz() {
        var t = leer("Memoria RAM Kingston Fury Beast DDR5 32GB 6000MHz Negra CL36");

        assertThat(t.velocidadMhz()).isEqualTo(6000);
        assertThat(t.ddr()).isEqualTo("DDR5");
        assertThat(t.capacidadGb()).isEqualTo(32);
    }

    // ── forma "3200 Mhz" (dos tokens contiguos) ──────────────────────────

    @Test
    void velocidadSeparadaDelSufijoMhz() {
        var t = leer("Memoria Ram Kingston 16GB 3200 Mhz DDR4");

        assertThat(t.velocidadMhz()).isEqualTo(3200);
        assertThat(t.ddr()).isEqualTo("DDR4");
    }

    // ── forma pelada: sólo un número, sin "mhz" en ningún lado ───────────

    @Test
    void velocidadPeladaAceptadaSoloConWhitelistYDdrDeclarado() {
        var t = leer("MEMORIA 8GB DDR5 6000 KINGSTON FURY BEAST RGB EXPO XMP");

        assertThat(t.velocidadMhz()).isEqualTo(6000);
        assertThat(t.ddr()).isEqualTo("DDR5");
    }

    @Test
    void numeroFueraDeLaWhitelistNoSeLeeComoVelocidad() {
        // 2199 no es una velocidad DDR real: un número pelado fuera de la
        // whitelist es indistinguible de un número de modelo.
        var t = leer("Memoria RAM Generica DDR4 16GB 2199");

        assertThat(t.velocidadMhz()).isZero();
    }

    @Test
    void numeroPeladoSinDdrDeclaradoNoSeLeeComoVelocidad() {
        // 3200 esta en la whitelist, pero sin "DDRn" en el nombre un numero
        // suelto de 4 digitos podria ser cualquier otra cosa.
        var t = leer("Memoria RAM Generica 16GB 3200");

        assertThat(t.velocidadMhz()).isZero();
        assertThat(t.ddr()).isEmpty();
    }

    @Test
    void velocidadPeladaEnElFixtureDeFase1() {
        // Mismo fixture que TechSpecsParserTest#ramSodimmDdr3: "1600" esta
        // en la whitelist y el nombre declara DDR3, asi que tambien se lee.
        var t = leer("MEMORIA SODIMM 8GB DDR3 1600 HIKSEMI");

        assertThat(t.velocidadMhz()).isEqualTo(1600);
    }

    @Test
    void sinNingunNumeroCompatibleAbstiene() {
        var t = leer("Memoria RAM Kingston Fury DDR5 CL30");

        assertThat(t.velocidadMhz()).isZero();
    }

    // ── modulos (T3b, pc-builder-deep-taxonomy) ─────────────────────────

    @Test
    void modulosSeLeeDelKitConMultiplicador() {
        var t = leer("Memoria RAM Patriot Viper RGB 32GB (2x16GB) 5600 MHz DDR5");

        assertThat(t.modulos()).isEqualTo(2);
    }

    @Test
    void modulosSeLeeDelKitConMultiplicadorSegundoFixture() {
        var t = leer("Memoria Team DDR4 32GB (2x16GB) 3200MHz T-Force Vulcan Z Grey CL16");

        assertThat(t.modulos()).isEqualTo(2);
    }

    @Test
    void modulosAbstieneEnUnNgbStandaloneSinMultiplicador() {
        // Un solo "32GB" sin "(NxMGB)" no dice si es un stick o un kit —
        // no se asume 1.
        var t = leer("Memoria RAM Kingston Fury Beast DDR5 32GB 6000MHz Negra CL36");

        assertThat(t.modulos()).isZero();
    }

    @Test
    void ddr2SeLeeAunqueNoEntreEnNingunaMotherDeHoy() {
        // Una sola fila en el catálogo, pero sin leerla el reader abstiene y
        // ReglaDdr no puede vetarla: una Kimota DDR2 2GB de 2007 entraba en un
        // armado con mother DDR5 y ganaba el slot por ser lo más barato.
        assertThat(leer("Memoria RAM Kimota DDR2 2GB 800MHz").ddr()).isEqualTo("DDR2");
    }
}
