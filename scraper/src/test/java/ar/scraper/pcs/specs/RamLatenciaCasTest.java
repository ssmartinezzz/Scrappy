package ar.scraper.pcs.specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RamSpecsReader — latenciaCl from CLnn / Cnn / CL nn, 0 = abstention")
class RamLatenciaCasTest {

    private final RamSpecsReader reader = new RamSpecsReader();

    private int cl(String nombre) {
        return reader.leer(Tokens.de(nombre)).latenciaCl();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Memoria RAM Kingston Fury Beast DDR5 32GB 6000MHz Negra RGB CL30 | 30",
            "Memoria Ram Ddr5 16gb 5600mhz Cl46 Adata Xpg Lancer Blade Rgb White | 46",
            "Memoria Ram DDR4 16Gb 2400Mhz Corsair Vengeance C16 LPX | 16",
            "Memoria Corsair DDR4 32GB (2x16GB) 3200MHz Vengeance C16 | 16",
            "Memoria G.Skill DDR5 32GB 6000MHz Trident Z5 CL 30 | 30",
            "Memoria G.Skill DDR5 32GB 6000MHz Trident Z5 CL30-38-38-96 | 30",
    })
    void leeLaLatenciaCas(String nombre, int esperado) {
        assertThat(cl(nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Memoria RAM Kingston Fury Beast DDR5 16GB 6000MHz",
            "Memoria Corsair Vengeance CMK16GX4M2B3200C16 DDR4 16GB",
            "Memoria Adata DDR4 16GB 3200MHz XPG Spectrix D35G RGB",
            "Memoria Kingston DDR5 16GB 5600MHz C5 Black",
    })
    void abstieneSinUnaLatenciaExplicita(String nombre) {
        assertThat(cl(nombre)).isZero();
    }
}
