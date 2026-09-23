package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `Conjunto` es una categoría de ROPA y KW_CONJUNTO corría antes de OFICINA y
 * TECH: {@code "combo"}/{@code " kit "}/{@code " pack "} se llevaban 348 filas
 * de tecnologia (contra 212 de indumentaria legítimas), incluidas 41 PCs que
 * {@code KW_PC_LIDER} nunca llegaba a ver. Nombres sacados de la base.
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("Conjunto must not outrank tech and office")
@DisplayName("CategoryClassifier — Conjunto es ropa, no gana sobre hardware")
class ConjuntoBeforeTechTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "KIT TECLADO Y MOUSE BLUETOOTH LOGITECH MK250 920-013513                       | Teclado",
        "Combo Teclado y Mouse Logitech MK270 Inalambrico                              | Teclado",
        "Combo Teclado y Mouse Genius KM-160 Español USB Negro                         | Teclado",
        "PC ARMADA AMD RYZEN 5 8600G+A620+16GB+SSD 480GB+GABINETE KIT                  | PC",
        "PC ARMADA AMD RYZEN 5 5600GT+A520+8GB+500GB M.2 NVME+GABINETE KIT             | PC",
        "Memoria RAM KLEVV FIT V DDR5 32GB Kit (2x16GB) 6000MHz Blanca CL30            | RAM",
        "Access Point Mesh WIFI Tp-Link Deco S7 Pack 2 Unidades AC1900                 | Red",
    })
    void unComboDeHardwareEsHardware(String nombre, String esperada) {
        assertThat(cat(nombre.trim())).isEqualTo(esperada);
    }

    @Test
    @DisplayName("los bundles mother+CPU salen de Conjunto y caen en una categoría de hardware")
    void losBundlesMotherCpuDejanDeSerRopa() {
        // Cuál de las dos gana lo decide el orden de `clasificarTech`, no este cambio.
        for (String nombre : new String[]{
                "Kit Mother ASUS PRIME A520M-K + Procesador AMD Ryzen 5 5600GT 4.6GHz Turbo AM4 + Wraith Stealth Cooler",
                "Kit Mother Asrock B850 RIPTIDE WIFI + Procesador AMD Ryzen 5 9600X 5.4GHz Turbo AM5 - No Incluye Cooler"}) {
            assertThat(cat(nombre))
                .as("bundle mother+CPU: %s", nombre)
                .isIn("Motherboard", "CPU");
        }
    }

    // Regresión ADR-4: si estos se ponen rojos, el arreglo rompió lo que
    // KW_CONJUNTO existía para proteger.

    @ParameterizedTest(name = "[{index}] {0} -> Conjunto")
    @CsvSource(delimiter = '|', value = {
        "PACK MUSCULOSA OVERSIZE SIGNAL NEGRO + SHORT DRYFORCE NEGRO |x",
        "Conjunto Deportivo Mujer Calza Y Top                        |x",
        "Combo Remera y Short Running Hombre                         |x",
        "Set de 2 piezas Buzo y Pantalon Niño                        |x",
    })
    void unComboDeRopaSigueSiendoConjunto(String nombre, String ignorado) {
        assertThat(cat(nombre.trim())).isEqualTo("Conjunto");
    }
}
