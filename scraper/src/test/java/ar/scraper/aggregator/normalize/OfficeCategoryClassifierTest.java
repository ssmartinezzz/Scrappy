package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * add-inpro-office-store: el vocabulario de {@code categoria} para el rubro
 * {@code oficina}.
 *
 * <p><b>Todos los nombres de este test son reales.</b> Salieron del catálogo
 * vivo de INPRO (100 productos únicos leídos de las 15 páginas de categoría el
 * 2026-08-19), no de imaginar qué vende una tienda de oficina. Eso importa
 * porque las trampas que fija este test no se habrían adivinado: son las que
 * el catálogo real tiene.</p>
 *
 * <p>Las cuatro colisiones que el orden del if-chain resuelve, y que son la
 * razón de que el bloque de oficina corra ANTES del bloque TECH:</p>
 * <ul>
 *   <li>{@code "Brazo de Monitor"} contiene {@code "monitor "} — {@code KW_MONITOR}
 *       lo convertiría en una PANTALLA.</li>
 *   <li>{@code "Soporte de CPU para Standing Desk"} contiene {@code "cpu "} —
 *       {@code KW_CPU} lo convertiría en un PROCESADOR.</li>
 *   <li>{@code "Lámpara de Monitor LED"} es iluminación, no un soporte: por eso
 *       {@code Iluminación} se evalúa antes que {@code Soporte Monitor}.</li>
 *   <li>{@code "Soporte de Notebook para Silla Ergonómica"} contiene
 *       {@code " silla "}: por eso {@code Soporte Laptop} se evalúa antes que
 *       {@code Silla}.</li>
 * </ul>
 */
@Epic("Normalization")
@Feature("Category")
@DisplayName("CategoryClassifier — vocabulario del rubro oficina")
class OfficeCategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String clasificar(String nombre) {
        return classifier.normalizarCategoria(null, nombre);
    }

    @Nested
    @DisplayName("Las siete categorías canónicas de oficina")
    class CanonicalCategories {

        @ParameterizedTest(name = "\"{0}\" -> {1}")
        @CsvSource({
                // ── Silla ──────────────────────────────────────────────────
                "'Silla Ergonómica Pro',                      Silla",
                "'Silla Ergonómica Recline',                  Silla",
                "'Silla Ergonómica Kids',                     Silla",
                "'Silla Yoga V2',                             Silla",
                "'Silla Freedom',                             Silla",
                "'Stool INPRO',                               Silla",

                // ── Escritorio ─────────────────────────────────────────────
                "'Standing Desk Pro V2',                      Escritorio",
                "'Standing Desk Pro Ultra',                   Escritorio",
                "'Doble Standing Desk Pro',                   Escritorio",
                "'Standing Desk Móvil',                       Escritorio",
                "'Standing Desk Oval Pro Duo',                Escritorio",
                "'Standing Desk Kids',                        Escritorio",

                // ── Soporte Monitor ────────────────────────────────────────
                "'Brazo de Monitor',                          'Soporte Monitor'",
                "'Brazo de Monitor Doble',                    'Soporte Monitor'",
                "'Estante para Monitor Premium',              'Soporte Monitor'",

                // ── Soporte Laptop ─────────────────────────────────────────
                "'Soporte para Laptop Vertical',              'Soporte Laptop'",
                "'Soporte para Laptop 360',                   'Soporte Laptop'",
                "'Soporte para Laptop Wood',                  'Soporte Laptop'",

                // ── Iluminación ────────────────────────────────────────────
                "'Lámpara de Escritorio Led',                 Iluminación",
                "'Lámpara Glow Tall',                         Iluminación",
                "'Lampara Hexagon',                           Iluminación",
                "'Mushroom Lamp',                             Iluminación",
                "'Iron Lamp',                                 Iluminación",
                "'Magnetic Lamp',                             Iluminación",
                "'Paneles Led RGB Triangulares Smart',        Iluminación",

                // ── Mat Escritorio ─────────────────────────────────────────
                "'Mat de Escritorio de Ecocuero',             'Mat Escritorio'",
                "'Mat de Escritorio Edge',                    'Mat Escritorio'",
                "'Mat Antifatiga',                            'Mat Escritorio'",
                "'Mat Antifatiga Pro',                        'Mat Escritorio'",
                "'Mat Board',                                 'Mat Escritorio'",

                // ── Organización ───────────────────────────────────────────
                "'Organizador Pasacable Vertebra',            Organización",
                "'Organizador de Cables',                     Organización",
                "'Pegboard',                                  Organización",
                "'Cajonera Moderna INPRO',                    Organización",
                "'Cubre Cables',                              Organización",
                "'Bandeja Portacables (Cable Tray)',          Organización",
        })
        void nombreRealDelCatalogoResuelveASuCategoria(String nombre, String esperada) {
            assertThat(clasificar(nombre)).isEqualTo(esperada);
        }
    }

    @Nested
    @DisplayName("El bloque de oficina le gana al bloque TECH (colisiones reales)")
    class BeatsTechBlock {

        @Test
        @DisplayName("\"Brazo de Monitor\" es un soporte, no una pantalla")
        void brazoDeMonitorNoEsUnMonitor() {
            assertThat(clasificar("Brazo de Monitor")).isNotEqualTo("Monitor");
        }

        @Test
        @DisplayName("\"Estante para Monitor Premium\" es un soporte, no una pantalla")
        void estanteParaMonitorNoEsUnMonitor() {
            assertThat(clasificar("Estante para Monitor Premium")).isNotEqualTo("Monitor");
        }

        @Test
        @DisplayName("\"Lámpara de Monitor LED\" es iluminación, no una pantalla ni un soporte")
        void lamparaDeMonitorEsIluminacion() {
            assertThat(clasificar("Lámpara de Monitor LED")).isEqualTo("Iluminación");
        }

        @Test
        @DisplayName("\"Soporte de CPU para Standing Desk\" no es un procesador")
        void soporteDeCpuNoEsUnCpu() {
            assertThat(clasificar("Soporte de CPU para Standing Desk"))
                    .isNotEqualTo("CPU")
                    .isEqualTo("Organización");
        }

        @Test
        @DisplayName("\"Soporte de Notebook para Silla Ergonómica\" es un soporte, no una silla")
        void soporteDeNotebookNoEsUnaSilla() {
            assertThat(clasificar("Soporte de Notebook para Silla Ergonómica"))
                    .isEqualTo("Soporte Laptop");
        }
    }

    @Nested
    @DisplayName("Una parte o un servicio de escritorio NO es un escritorio")
    class DeskPartsAreNotDesks {

        @ParameterizedTest(name = "\"{0}\" no es un Escritorio")
        @CsvSource({
                "'Servicio de instalación de Standing Desk'",
                "'Tapa Premium Standing Desk'",
                "'Ruedas Standing Desk'",
        })
        void parteOServicioNoEsEscritorio(String nombre) {
            assertThat(clasificar(nombre)).isNotEqualTo("Escritorio");
        }

        @Test
        @DisplayName("Un cajón de standing desk es organización, no un escritorio")
        void cajonDeStandingDeskEsOrganizacion() {
            assertThat(clasificar("Cajón Standing Desk")).isEqualTo("Organización");
            assertThat(clasificar("Cajón Standing Desk Felpa")).isEqualTo("Organización");
        }

        @Test
        @DisplayName("Un standing desk sin tapa sigue siendo un escritorio")
        void estructuraSolaSigueSiendoEscritorio() {
            assertThat(clasificar("Standing Desk Pro Solo Estructura")).isEqualTo("Escritorio");
        }
    }

    @Nested
    @DisplayName("El vocabulario nuevo no le roba nada al viejo")
    class NoRegression {

        @ParameterizedTest(name = "\"{0}\" sigue siendo {1}")
        @CsvSource({
                // El bloque TECH sigue intacto para productos que SÍ son tech.
                "'Monitor Samsung 24 pulgadas',        Monitor",
                "'Procesador Intel Core i5 12400F',    CPU",
                "'Teclado Gamer RGB',                  Teclado",
                // OJO: "Teclado Mecanico RGB" da "Otros", no "Teclado" — la
                // frase esta en NO_TEXTIL_INICIO de NonTextileGuard, que hace
                // abstener a clasificar() ANTES de que KW_TECLADO la vea. Es un
                // bug preexistente y ajeno a este cambio; se fija acá el
                // comportamiento REAL para que el dia que se arregle se vea.
                "'Teclado Mecanico RGB',               Otros",
                "'Notebook Lenovo IdeaPad',            Notebook",
                // KW_PC usa "computadora de escritorio"/"equipo de escritorio":
                // por eso "escritorio" pelado NO puede ser keyword de Escritorio.
                "'Computadora de Escritorio Gamer',    PC",
                // Indumentaria y calzado, sin cambios.
                "'Zapatilla Running Nike',             'Zapatilla Running'",
                "'Remera Oversize Negra',              Remera",
                "'Campera Puffer Inflable',            Puffer",
        })
        void categoriaPreviaNoCambia(String nombre, String esperada) {
            assertThat(clasificar(nombre)).isEqualTo(esperada);
        }
    }

    @Nested
    @DisplayName("Las siete categorías entran al canon que el agente LLM acepta")
    class CanonMembership {

        @ParameterizedTest(name = "{0} es una categoría canónica")
        @CsvSource({"Silla", "Escritorio", "'Soporte Monitor'", "'Soporte Laptop'",
                    "Iluminación", "'Mat Escritorio'", "Organización"})
        void categoriaDeOficinaEstaEnElCanon(String categoria) {
            assertThat(CategoryGroups.canonicalCategories()).contains(categoria);
        }

        @Test
        @DisplayName("Ninguna categoría de oficina es indumentaria, calzado ni suplemento")
        void categoriasDeOficinaNoSonDeOtroRubro() {
            for (String cat : CategoryGroups.categoriasOficina()) {
                assertThat(CategoryGroups.esIndumentariaOCalzado(cat))
                        .as("%s no puede contar como indumentaria/calzado", cat).isFalse();
                assertThat(CategoryGroups.esCategoriaSuplemento(cat))
                        .as("%s no puede contar como suplemento", cat).isFalse();
            }
        }
    }
}
