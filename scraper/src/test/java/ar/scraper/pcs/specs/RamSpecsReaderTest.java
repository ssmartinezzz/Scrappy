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
}
